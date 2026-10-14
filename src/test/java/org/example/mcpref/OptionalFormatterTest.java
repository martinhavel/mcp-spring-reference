package org.example.mcpref;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import org.example.mcpref.format.VerifiedResultFormatter;
import org.example.mcpref.governance.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * AC2 and AC10, with a stand-in for an LLM formatter plugged into the only extension point.
 * (All other test classes run with no formatter at all, which is the v1 default: AC10.)
 */
@Import(OptionalFormatterTest.FakeLlmConfig.class)
class OptionalFormatterTest extends IntegrationTestBase {

    static final List<ToolResult> SEEN_BY_LLM = new CopyOnWriteArrayList<>();
    static final AtomicBoolean LLM_DOWN = new AtomicBoolean();

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        VerifiedResultFormatter fakeLlm() {
            return result -> {
                SEEN_BY_LLM.add(result);
                if (LLM_DOWN.get()) {
                    throw new IllegalStateException("LLM endpoint unreachable");
                }
                return "Company " + result.ico() + " found (source: " + result.provenance().toolName() + ").";
            };
        }
    }

    @AfterEach
    void reset() {
        SEEN_BY_LLM.clear();
        LLM_DOWN.set(false);
    }

    @Test
    @DisplayName("AC2 the formatter (LLM slot) never sees credentials: not the upstream token, not user or DB passwords")
    void formatterNeverSeesCredentials() throws Exception {
        ResponseEntity<JsonNode> response = asAnalyst("/api/companies/" + VALID_ICO, "ac2-no-secrets");

        // The token is really in use on the wire...
        UPSTREAM.verify(postRequestedFor(urlEqualTo("/mcp")).withHeader("Authorization", equalTo("Bearer " + UPSTREAM_TOKEN)));
        // ...but never in what the formatter receives, what the user receives, or the audit trail.
        String formatterInput = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(SEEN_BY_LLM);
        String everythingVisible = formatterInput + response.getBody() + auditRows("ac2-no-secrets");
        assertThat(SEEN_BY_LLM).hasSize(1);
        for (String secret : List.of(UPSTREAM_TOKEN, ANALYST_PASSWORD, COMPLIANCE_PASSWORD, DB_APP_PASSWORD, DB_OWNER_PASSWORD, "Bearer")) {
            assertThat(everythingVisible).doesNotContain(secret);
        }
    }

    @Test
    @DisplayName("AC2 an upstream that echoes the Authorization header is rejected; the token reaches no result, audit or formatter")
    void reflectedCredentialIsRejected() throws Exception {
        stubTool("lookup_by_ico", rpcResult(textContent("echo: {{request.headers.Authorization}}")));

        ResponseEntity<JsonNode> response = asAnalyst("/api/companies/" + VALID_ICO, "ac2-reflected-token");

        // The stub really did receive (and therefore echo) the token.
        UPSTREAM.verify(postRequestedFor(urlEqualTo("/mcp")).withHeader("Authorization", equalTo("Bearer " + UPSTREAM_TOKEN)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().path("outcome").asText()).isEqualTo("UNAVAILABLE");
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(response.getBody().has("rawResponse")).isFalse();
        assertThat(SEEN_BY_LLM).isEmpty();

        var audit = auditRows("ac2-reflected-token");
        assertThat(audit).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("outcome", "UNAVAILABLE")
                .containsEntry("result_sha256", null));
        assertThat(audit.get(0).get("detail").toString())
                .isEqualTo("Upstream localhost reflected the configured credential; response discarded");

        String formatterInput = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(SEEN_BY_LLM);
        assertThat(formatterInput + response.getBody() + audit).doesNotContain(UPSTREAM_TOKEN);
    }

    @Test
    @DisplayName("AC2 a token echoed with JSON unicode escapes is still detected after decoding and rejected")
    void unicodeEscapedCredentialIsRejected() {
        // Every character escaped: the raw body never contains the token, but the decoded JSON does.
        String escaped = UPSTREAM_TOKEN.chars().mapToObj(c -> String.format("\\u%04x", c)).collect(Collectors.joining());
        stubTool("lookup_by_ico", rpcResult("{\"content\":[{\"type\":\"text\",\"text\":\"echo: " + escaped + "\"}]}"));

        ResponseEntity<JsonNode> response = asAnalyst("/api/companies/" + VALID_ICO, "ac2-escaped-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().path("outcome").asText()).isEqualTo("UNAVAILABLE");
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(response.getBody().has("rawResponse")).isFalse();
        assertThat(SEEN_BY_LLM).isEmpty();
        assertThat(auditRows("ac2-escaped-token")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("outcome", "UNAVAILABLE")
                .containsEntry("result_sha256", null)
                .containsEntry("detail", "Upstream localhost reflected the configured credential; response discarded"));
    }

    @Test
    @DisplayName("AC2 SSE: an escaped token in an event AFTER the matching one is still detected and rejected")
    void credentialInTrailingSseEventIsRejected() {
        String escaped = UPSTREAM_TOKEN.chars().mapToObj(c -> String.format("\\u%04x", c)).collect(Collectors.joining());

        // Control: matching event + harmless trailing event -> success, and only the selected event is passed on.
        stubTool("lookup_by_ico", sse("{\"level\":\"info\",\"data\":\"done\"}"));
        ResponseEntity<JsonNode> ok = asAnalyst("/api/companies/" + VALID_ICO, "ac2-sse-clean");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().path("rawResponse").asText()).doesNotContain("notifications/message");
        SEEN_BY_LLM.clear();

        // Same stream, but the trailing event carries the \\u-escaped token.
        stubTool("lookup_by_ico", sse("{\"level\":\"info\",\"data\":\"echo: " + escaped + "\"}"));
        ResponseEntity<JsonNode> response = asAnalyst("/api/companies/" + VALID_ICO, "ac2-sse-trailing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().path("outcome").asText()).isEqualTo("UNAVAILABLE");
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(response.getBody().has("rawResponse")).isFalse();
        assertThat(SEEN_BY_LLM).isEmpty();
        assertThat(auditRows("ac2-sse-trailing")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("result_sha256", null));
    }

    /** An SSE stream: the matching JSON-RPC response, then a notification with the given params. */
    private ResponseDefinitionBuilder sse(String trailingParams) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":{{jsonPath request.body '$.id'}},\"result\":"
                        + textContent(COMPANY_FIXTURE) + "}\n\n"
                        + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":"
                        + trailingParams + "}\n\n");
    }

    @Test
    @DisplayName("AC2 the formatter is not consulted for authorization: a denied call never reaches it")
    void formatterNotInvolvedInAuthorization() {
        ResponseEntity<JsonNode> response = asAnalyst("/api/companies/" + VALID_ICO + "/vat", "ac2-denied");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(SEEN_BY_LLM).isEmpty();
    }

    @Test
    @DisplayName("AC10 the formatter only adds a summary; data and provenance are unchanged")
    void formatterOnlyAddsSummary() {
        JsonNode body = asAnalyst("/api/companies/" + VALID_ICO, "ac10-summary").getBody();

        assertThat(body.path("summary").asText()).isEqualTo("Company " + VALID_ICO + " found (source: lookup_by_ico).");
        assertThat(body.path("data").path("content").get(0).path("text").asText()).isEqualTo(COMPANY_FIXTURE);
        assertThat(body.path("provenance").path("responseSha256").asText())
                .isEqualTo(SEEN_BY_LLM.get(0).provenance().responseSha256());
    }

    @Test
    @DisplayName("AC10 a failing formatter does not break the call: verified result is returned without summary")
    void failingFormatterIsHarmless() {
        LLM_DOWN.set(true);

        ResponseEntity<JsonNode> response = asAnalyst("/api/companies/" + VALID_ICO, "ac10-llm-down");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().has("summary")).isFalse();
        assertThat(response.getBody().path("provenance").path("responseSha256").asText()).hasSize(64);
    }
}
