package org.example.mcpref;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
