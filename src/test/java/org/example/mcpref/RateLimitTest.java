package org.example.mcpref;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/** AC8 (rate limit part). Own Spring context so the limiter budget is not shared with other tests. */
@TestPropertySource(properties = "mcp.upstream.max-calls-per-minute=2")
class RateLimitTest extends IntegrationTestBase {

    @Test
    @DisplayName("AC8 calls beyond the upstream rate limit are refused locally as RATE_LIMITED and audited")
    void rateLimitIsEnforced() {
        assertThat(asAnalyst("/api/companies/" + VALID_ICO, "ac8-rl-1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asAnalyst("/api/companies/" + VALID_ICO, "ac8-rl-2").getStatusCode()).isEqualTo(HttpStatus.OK);

        var third = asAnalyst("/api/companies/" + VALID_ICO, "ac8-rl-3");

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getBody().path("outcome").asText()).isEqualTo("RATE_LIMITED");
        assertThat(auditRows("ac8-rl-3")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("outcome", "RATE_LIMITED"));
        UPSTREAM.verify(2, postRequestedFor(urlEqualTo("/mcp"))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call"))));
    }
}
