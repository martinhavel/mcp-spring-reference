package org.example.mcpref;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.http.Fault;
import org.example.mcpref.format.VerifiedResultFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GovernedToolCallTest extends IntegrationTestBase {

    @Autowired
    ApplicationContext context;

    // --- AC1: role allowlist -----------------------------------------------------------------

    @Test
    @DisplayName("AC1 analyst may call company lookup")
    void analystMayLookUp() {
        ResponseEntity<JsonNode> response = asAnalyst("/api/companies/" + VALID_ICO, "ac1-lookup-ok");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(auditRows("ac1-lookup-ok")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("principal", "analyst").containsEntry("outcome", "SUCCESS"));
    }

    @Test
    @DisplayName("AC1 analyst calling VAT check is denied, audited, and upstream is never contacted")
    void analystDeniedVatCheck() {
        ResponseEntity<JsonNode> response = asAnalyst("/api/companies/" + VALID_ICO + "/vat", "ac1-vat-denied");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().path("outcome").asText()).isEqualTo("DENIED");
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(auditRows("ac1-vat-denied")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("principal", "analyst")
                .containsEntry("roles", "ROLE_ANALYST")
                .containsEntry("tool", "check_vat_payer")
                .containsEntry("outcome", "DENIED"));
        UPSTREAM.verify(0, postRequestedFor(urlEqualTo("/mcp"))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call"))));
    }

    @Test
    @DisplayName("AC1 compliance may call VAT check")
    void complianceMayCheckVat() {
        ResponseEntity<JsonNode> response = asCompliance("/api/companies/" + VALID_ICO + "/vat", "ac1-vat-ok");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("data").path("content").get(0).path("text").asText()).isEqualTo(VAT_FIXTURE);
    }

    @Test
    @DisplayName("AC1 unauthenticated requests are rejected before reaching the service")
    void unauthenticatedRejected() {
        ResponseEntity<JsonNode> response = get("/api/companies/" + VALID_ICO, null, null, "ac1-anon");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo("ac1-anon");
    }

    // --- AC3: only validated IČO reaches the tool --------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {BAD_CHECKSUM_ICO, "1234567", "123456790", "abcdefgh", "1234567X", "12345679 OR 1=1"})
    @DisplayName("AC3 invalid IČO is rejected, audited, and never sent upstream")
    void invalidIcoRejected(String input) {
        String traceId = "ac3-invalid-" + Math.abs(input.hashCode());
        ResponseEntity<JsonNode> response = asCompliance("/api/companies/" + input, traceId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("outcome").asText()).isEqualTo("INVALID_INPUT");
        assertThat(auditRows(traceId)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("outcome", "INVALID_INPUT"));
        UPSTREAM.verify(0, postRequestedFor(urlEqualTo("/mcp")));
    }

    @Test
    @DisplayName("AC3 the upstream receives exactly the validated IČO as the only argument")
    void onlyValidatedIcoIsSent() {
        asCompliance("/api/companies/" + OTHER_VALID_ICO, "ac3-args");

        UPSTREAM.verify(1, postRequestedFor(urlEqualTo("/mcp")).withRequestBody(equalToJson(
                "{\"jsonrpc\":\"2.0\",\"id\":\"${json-unit.any-number}\",\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"lookup_by_ico\",\"arguments\":{\"ico\":\"" + OTHER_VALID_ICO + "\"}}}")));
    }

    // --- AC4: provenance ---------------------------------------------------------------------

    @Test
    @DisplayName("AC4 every result carries provenance whose hash can be recomputed from the raw response")
    void provenanceIsReproducible() throws Exception {
        Instant before = Instant.now();
        JsonNode body = asAnalyst("/api/companies/" + VALID_ICO, "ac4-provenance").getBody();

        JsonNode provenance = body.path("provenance");
        assertThat(provenance.path("sourceUrl").asText()).isEqualTo(UPSTREAM.baseUrl() + "/mcp");
        assertThat(provenance.path("toolName").asText()).isEqualTo("lookup_by_ico");
        assertThat(provenance.path("adapterVersion").asText()).isEqualTo("1.0.0");
        String fetchedAt = provenance.path("fetchedAt").asText();
        assertThat(fetchedAt).endsWith("Z");
        assertThat(Instant.parse(fetchedAt)).isAfterOrEqualTo(before.minusSeconds(1));

        String recomputed = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.path("rawResponse").asText().getBytes(StandardCharsets.UTF_8)));
        assertThat(provenance.path("responseSha256").asText()).isEqualTo(recomputed);
        assertThat(auditRows("ac4-provenance")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("result_sha256", recomputed));
        assertThat(body.path("data").path("content").get(0).path("text").asText()).isEqualTo(COMPANY_FIXTURE);
    }

    @Test
    @DisplayName("AC4 an upstream tool error is reported as UPSTREAM_ERROR, still with provenance")
    void upstreamErrorKeepsProvenance() {
        stubTool("lookup_by_ico", rpcError(-32602, "Invalid params"));

        ResponseEntity<JsonNode> response = asAnalyst("/api/companies/" + VALID_ICO, "ac4-upstream-error");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().path("outcome").asText()).isEqualTo("UPSTREAM_ERROR");
        assertThat(response.getBody().path("provenance").path("responseSha256").asText()).hasSize(64);
    }

    // --- AC6: explicit UNAVAILABLE -----------------------------------------------------------

    @Test
    @DisplayName("AC6 upstream HTTP 503 yields UNAVAILABLE with no data")
    void upstream503IsUnavailable() {
        stubTool("lookup_by_ico", aResponse().withStatus(503));
        assertUnavailable(asAnalyst("/api/companies/" + VALID_ICO, "ac6-http-503"), "ac6-http-503");
    }

    @Test
    @DisplayName("AC6 upstream connection reset yields UNAVAILABLE with no data")
    void upstreamConnectionResetIsUnavailable() {
        stubTool("lookup_by_ico", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));
        assertUnavailable(asAnalyst("/api/companies/" + VALID_ICO, "ac6-reset"), "ac6-reset");
    }

    // --- AC8: timeout (rate limit is in RateLimitTest) ---------------------------------------

    @Test
    @DisplayName("AC8 a slow upstream is cut off by the request timeout and reported as UNAVAILABLE")
    void slowUpstreamTimesOut() {
        stubTool("lookup_by_ico", rpcResult(textContent(COMPANY_FIXTURE)).withFixedDelay(3_000));

        long start = System.nanoTime();
        ResponseEntity<JsonNode> response = asAnalyst("/api/companies/" + VALID_ICO, "ac8-timeout");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertUnavailable(response, "ac8-timeout");
        assertThat(response.getBody().path("message").asText()).contains("unavailable");
        assertThat(auditRows("ac8-timeout").get(0).get("detail").toString()).contains("timed out");
        assertThat(elapsedMs).isLessThan(2_500);
    }

    // --- AC9: trace id -----------------------------------------------------------------------

    @Test
    @DisplayName("AC9 the caller's trace id reaches the upstream call, the audit row and the response")
    void traceIdPropagatesEndToEnd() {
        ResponseEntity<JsonNode> response = asCompliance("/api/companies/" + VALID_ICO + "/vat", "ac9-trace-0001");

        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo("ac9-trace-0001");
        assertThat(response.getBody().path("traceId").asText()).isEqualTo("ac9-trace-0001");
        UPSTREAM.verify(postRequestedFor(urlEqualTo("/mcp"))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
                .withHeader("X-Trace-Id", equalTo("ac9-trace-0001")));
        assertThat(auditRows("ac9-trace-0001")).hasSize(1);
    }

    @Test
    @DisplayName("AC9 a missing or malformed trace id is replaced by a generated one, used consistently")
    void traceIdGeneratedWhenAbsent() {
        ResponseEntity<JsonNode> response = asAnalyst("/api/companies/" + VALID_ICO, "bad id!");

        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).isNotEqualTo("bad id!").matches("[0-9a-f-]{36}");
        assertThat(response.getBody().path("traceId").asText()).isEqualTo(traceId);
        assertThat(auditRows(traceId)).hasSize(1);
    }

    // --- AC10: no LLM layer ------------------------------------------------------------------

    @Test
    @DisplayName("AC10 the default build has no formatter/LLM bean and the full pipeline works without one")
    void worksWithoutAnyFormatter() {
        assertThat(context.getBeanNamesForType(VerifiedResultFormatter.class)).isEmpty();

        JsonNode body = asAnalyst("/api/companies/" + VALID_ICO, "ac10-no-llm").getBody();

        assertThat(body.path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(body.has("summary")).isFalse();
    }

    private void assertUnavailable(ResponseEntity<JsonNode> response, String traceId) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().path("outcome").asText()).isEqualTo("UNAVAILABLE");
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(response.getBody().has("provenance")).isFalse();
        List<Map<String, Object>> rows = auditRows(traceId);
        assertThat(rows).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("outcome", "UNAVAILABLE")
                .containsEntry("result_sha256", null));
    }
}
