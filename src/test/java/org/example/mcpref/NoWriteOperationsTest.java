package org.example.mcpref;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.mcpref.governance.GovernedTool;
import org.example.mcpref.ico.Ico;
import org.example.mcpref.mcp.McpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** AC7: there is no write operation anywhere - not in the API, not towards upstream, not in the database. */
class NoWriteOperationsTest extends IntegrationTestBase {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("AC7 every application endpoint is GET-only")
    void allEndpointsAreGet() {
        Set<RequestMethod> methods = handlerMapping.getHandlerMethods().keySet().stream()
                .filter(info -> info.getPatternValues().stream().anyMatch(p -> p.startsWith("/api/")))
                .flatMap(info -> info.getMethodsCondition().getMethods().stream())
                .collect(Collectors.toSet());

        assertThat(methods).containsExactly(RequestMethod.GET);
    }

    @Test
    @DisplayName("AC7 non-GET requests are refused by the security filter chain")
    void nonGetIsRefused() {
        for (HttpMethod method : new HttpMethod[]{HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH}) {
            var response = rest.withBasicAuth("compliance", COMPLIANCE_PASSWORD)
                    .exchange("/api/companies/" + VALID_ICO, method, HttpEntity.EMPTY, JsonNode.class);
            assertThat(response.getStatusCode()).as(method.name()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("AC7 the MCP client port only accepts an allowlisted tool and a validated IČO")
    void mcpPortIsNarrow() {
        Method[] methods = McpClient.class.getDeclaredMethods();
        assertThat(methods).hasSize(1);
        assertThat(methods[0].getParameterTypes()).containsExactly(GovernedTool.class, Ico.class, String.class);

        assertThat(Arrays.stream(GovernedTool.values()).map(GovernedTool::mcpName))
                .containsExactlyInAnyOrder("lookup_by_ico", "check_vat_payer");
    }

    @Test
    @DisplayName("AC7 only read-only MCP methods are ever sent upstream")
    void onlyReadOnlyMcpMethodsSent() throws Exception {
        asCompliance("/api/companies/" + VALID_ICO, "ac7-methods-1");
        asCompliance("/api/companies/" + VALID_ICO + "/vat", "ac7-methods-2");

        Set<String> sentMethods = new java.util.HashSet<>();
        Set<String> calledTools = new java.util.HashSet<>();
        for (var request : UPSTREAM.findAll(anyRequestedFor(anyUrl()))) {
            JsonNode body = mapper.readTree(request.getBodyAsString());
            sentMethods.add(body.path("method").asText());
            if ("tools/call".equals(body.path("method").asText())) {
                calledTools.add(body.path("params").path("name").asText());
            }
        }
        assertThat(Set.of("initialize", "notifications/initialized", "tools/list", "tools/call"))
                .containsAll(sentMethods);
        assertThat(calledTools).containsExactlyInAnyOrder("lookup_by_ico", "check_vat_payer");
    }
}
