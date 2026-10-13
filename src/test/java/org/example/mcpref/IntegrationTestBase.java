package org.example.mcpref;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Shared test infrastructure: a real PostgreSQL (initialised with the same {@code db/init}
 * script as docker-compose) and a WireMock server playing the upstream ARES MCP server.
 * All IČO values and company data here are synthetic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "mcp.upstream.request-timeout=1s",
        "mcp.upstream.max-calls-per-minute=1000"
})
public abstract class IntegrationTestBase {

    /** Synthetic, MOD11-valid test IČO values. */
    protected static final String VALID_ICO = "12345679";
    protected static final String OTHER_VALID_ICO = "87654326";
    /** Correct format, wrong check digit. */
    protected static final String BAD_CHECKSUM_ICO = "12345678";

    protected static final String ANALYST_PASSWORD = "test-analyst-pw";
    protected static final String COMPLIANCE_PASSWORD = "test-compliance-pw";
    protected static final String DB_OWNER_PASSWORD = "test-owner-pw";
    protected static final String DB_APP_PASSWORD = "test-app-pw";
    protected static final String UPSTREAM_TOKEN = "test-upstream-token-must-never-leak";

    protected static final String COMPANY_FIXTURE =
            "{\"ico\":\"12345679\",\"obchodniJmeno\":\"Example Trading s.r.o.\",\"dic\":\"CZ12345679\"}";
    protected static final String VAT_FIXTURE = "Example Trading s.r.o. - VAT PAYER. DIC: CZ12345679";

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("mcpref")
            .withUsername("postgres")
            .withPassword("test-superuser-pw")
            .withEnv("MCP_OWNER_PASSWORD", DB_OWNER_PASSWORD)
            .withEnv("MCP_APP_PASSWORD", DB_APP_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01-roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-roles.sh");

    protected static final WireMockServer UPSTREAM = new WireMockServer(options().dynamicPort().globalTemplating(true));

    static {
        POSTGRES.start();
        UPSTREAM.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/mcpref");
        registry.add("DB_APP_PASSWORD", () -> DB_APP_PASSWORD);
        registry.add("DB_OWNER_PASSWORD", () -> DB_OWNER_PASSWORD);
        registry.add("ANALYST_PASSWORD", () -> ANALYST_PASSWORD);
        registry.add("COMPLIANCE_PASSWORD", () -> COMPLIANCE_PASSWORD);
        registry.add("MCP_UPSTREAM_URL", () -> UPSTREAM.baseUrl() + "/mcp");
        registry.add("MCP_UPSTREAM_TOKEN", () -> UPSTREAM_TOKEN);
    }

    @Autowired
    protected TestRestTemplate rest;

    protected final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void resetUpstream() {
        UPSTREAM.resetAll();
        UPSTREAM.stubFor(rpc("initialize").willReturn(rpcResult(
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"0\"}}")
                .withHeader("Mcp-Session-Id", "fixture-session")));
        UPSTREAM.stubFor(rpc("notifications/initialized").willReturn(aResponse().withStatus(202)));
        // The real server offers more tools; the adapter must ignore everything outside its allowlist.
        UPSTREAM.stubFor(rpc("tools/list").willReturn(rpcResult("{\"tools\":["
                + "{\"name\":\"lookup_by_ico\"},{\"name\":\"check_vat_payer\"},"
                + "{\"name\":\"search_companies\"},{\"name\":\"get_statutaries\"}]}")));
        stubTool("lookup_by_ico", rpcResult(textContent(COMPANY_FIXTURE)));
        stubTool("check_vat_payer", rpcResult(textContent(VAT_FIXTURE)));
    }

    // --- upstream fixture helpers -------------------------------------------------------------

    protected static MappingBuilder rpc(String method) {
        return post(urlEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo(method)));
    }

    protected static MappingBuilder toolCall(String toolName) {
        return rpc("tools/call").withRequestBody(matchingJsonPath("$.params.name", equalTo(toolName)));
    }

    protected static void stubTool(String toolName, ResponseDefinitionBuilder response) {
        UPSTREAM.stubFor(toolCall(toolName).willReturn(response));
    }

    /** A JSON-RPC response echoing the request id (WireMock response templating). */
    protected static ResponseDefinitionBuilder rpcResult(String resultJson) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":{{jsonPath request.body '$.id'}},\"result\":" + resultJson + "}");
    }

    protected static ResponseDefinitionBuilder rpcError(int code, String message) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":{{jsonPath request.body '$.id'}},\"error\":{\"code\":" + code
                        + ",\"message\":\"" + message + "\"}}");
    }

    protected String textContent(String text) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + jsonString(text) + "}]}";
    }

    private static String jsonString(String text) {
        try {
            return new ObjectMapper().writeValueAsString(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --- API helpers --------------------------------------------------------------------------

    protected ResponseEntity<JsonNode> get(String path, String user, String password, String traceId) {
        HttpHeaders headers = new HttpHeaders();
        if (traceId != null) {
            headers.set("X-Trace-Id", traceId);
        }
        TestRestTemplate client = user == null ? rest : rest.withBasicAuth(user, password);
        return client.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> asAnalyst(String path, String traceId) {
        return get(path, "analyst", ANALYST_PASSWORD, traceId);
    }

    protected ResponseEntity<JsonNode> asCompliance(String path, String traceId) {
        return get(path, "compliance", COMPLIANCE_PASSWORD, traceId);
    }

    // --- database helpers ---------------------------------------------------------------------

    /** Reads the audit trail as the superuser (the application role cannot read it). */
    protected List<Map<String, Object>> auditRows(String traceId) {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return new JdbcTemplate(new SingleConnectionDataSource(c, true))
                    .queryForList("SELECT * FROM mcpref.audit_event WHERE trace_id = ? ORDER BY id", traceId);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected Connection connectAs(String user, String password) throws SQLException {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/mcpref?currentSchema=mcpref";
        return DriverManager.getConnection(url, user, password);
    }
}
