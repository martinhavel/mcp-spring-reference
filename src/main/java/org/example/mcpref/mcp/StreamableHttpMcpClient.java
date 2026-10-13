package org.example.mcpref.mcp;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.mcpref.governance.GovernedTool;
import org.example.mcpref.ico.Ico;
import org.example.mcpref.web.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Minimal MCP client over the streamable-HTTP transport (JSON-RPC 2.0 over HTTP POST).
 *
 * <p>Why not the official MCP Java SDK? It supports streamable HTTP, but it parses responses
 * into typed objects and does not expose the raw response bytes, which we need to hash for
 * provenance. It also brings a generic "call any tool" surface we would have to wrap anyway.
 * The subset needed here (initialize, tools/list, tools/call) is ~200 lines that a reviewer can
 * read end to end. Swapping in the SDK later only touches this class.
 *
 * <p>Only four JSON-RPC methods are ever sent: {@code initialize},
 * {@code notifications/initialized}, {@code tools/list} and {@code tools/call}.
 */
@Component
public class StreamableHttpMcpClient implements McpClient {

    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final int MAX_TOOL_LIST_PAGES = 10;
    private static final Logger log = LoggerFactory.getLogger(StreamableHttpMcpClient.class);

    private final McpProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String adapterVersion;
    private final HttpClient http;
    private final AtomicLong requestIds = new AtomicLong();

    private volatile String sessionId;
    private volatile String protocolVersion;
    private volatile Set<String> offeredTools;

    public StreamableHttpMcpClient(McpProperties properties, ObjectMapper mapper, Clock clock,
                                   @Value("${app.adapter-version}") String adapterVersion) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.adapterVersion = adapterVersion;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .proxy(ProxySelector.getDefault())
                .build();
    }

    @Override
    public RawToolResponse callTool(GovernedTool tool, Ico ico, String traceId) {
        try {
            return doCallTool(tool, ico, traceId);
        } catch (SessionExpiredException e) {
            log.info("Upstream MCP session expired, re-initializing");
            resetSession();
            return doCallTool(tool, ico, traceId);
        }
    }

    private RawToolResponse doCallTool(GovernedTool tool, Ico ico, String traceId) {
        ensureInitialized(traceId);
        if (!offeredTools.contains(tool.mcpName())) {
            throw new UpstreamUnavailableException("Upstream does not offer tool " + tool.mcpName());
        }
        ObjectNode params = mapper.createObjectNode().put("name", tool.mcpName());
        params.putObject("arguments").put(GovernedTool.ICO_ARGUMENT, ico.value());

        log.info("Calling upstream tool {}", tool.mcpName());
        Exchange exchange = request("tools/call", params, traceId);
        if (exchange.message().has("error")) {
            return new RawToolResponse(exchange.body(), exchange.message().get("error"), true, exchange.receivedAt());
        }
        JsonNode result = exchange.message().path("result");
        if (!result.isObject()) {
            throw new UpstreamUnavailableException("Upstream returned a malformed tools/call response");
        }
        return new RawToolResponse(exchange.body(), result, result.path("isError").asBoolean(false), exchange.receivedAt());
    }

    /** initialize -> notifications/initialized -> tools/list, once per session. */
    private synchronized void ensureInitialized(String traceId) {
        if (offeredTools != null) {
            return;
        }
        ObjectNode params = mapper.createObjectNode().put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "mcp-spring-reference").put("version", adapterVersion);
        JsonNode init = requireResult(request("initialize", params, traceId), "initialize");
        protocolVersion = init.path("protocolVersion").asText(PROTOCOL_VERSION);

        notify("notifications/initialized", traceId);
        offeredTools = listTools(traceId);
        log.info("Upstream MCP session ready; governed tools offered: {}",
                GovernedTool.mcpNames().stream().filter(offeredTools::contains).sorted().toList());
    }

    private Set<String> listTools(String traceId) {
        Set<String> names = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_TOOL_LIST_PAGES; page++) {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = requireResult(request("tools/list", params, traceId), "tools/list");
            result.path("tools").forEach(t -> names.add(t.path("name").asText()));
            cursor = result.path("nextCursor").asText(null);
            if (cursor == null) {
                break;
            }
        }
        return Set.copyOf(names);
    }

    private synchronized void resetSession() {
        sessionId = null;
        protocolVersion = null;
        offeredTools = null;
    }

    private JsonNode requireResult(Exchange exchange, String method) {
        JsonNode result = exchange.message().path("result");
        if (exchange.message().has("error") || !result.isObject()) {
            throw new UpstreamUnavailableException("Upstream rejected " + method);
        }
        return result;
    }

    private void notify(String method, String traceId) {
        ObjectNode message = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        HttpResponse<byte[]> response = send(message, traceId);
        if (response.statusCode() != 202 && response.statusCode() != 200) {
            throw new UpstreamUnavailableException("Upstream answered HTTP " + response.statusCode() + " to " + method);
        }
    }

    private Exchange request(String method, ObjectNode params, String traceId) {
        long id = requestIds.incrementAndGet();
        ObjectNode message = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        message.set("params", params);

        HttpResponse<byte[]> response = send(message, traceId);
        if (response.statusCode() == 404 && sessionId != null && !"initialize".equals(method)) {
            throw new SessionExpiredException();
        }
        if (response.statusCode() != 200) {
            throw new UpstreamUnavailableException("Upstream answered HTTP " + response.statusCode() + " to " + method);
        }
        if ("initialize".equals(method)) {
            sessionId = response.headers().firstValue(SESSION_HEADER).orElse(null);
        }
        byte[] body = response.body();
        rejectIfCredentialReflected(body);
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        JsonNode reply = contentType.startsWith("text/event-stream")
                ? findInEventStream(body, id)
                : parseChecked(body);
        if (reply == null || reply.path("id").asLong(-1) != id) {
            throw new UpstreamUnavailableException("Upstream reply to " + method + " has no matching JSON-RPC id");
        }
        return new Exchange(body, reply, clock.instant());
    }

    private HttpResponse<byte[]> send(ObjectNode message, String traceId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(properties.url())
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header(TraceIdFilter.HEADER, traceId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(toBytes(message)));
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        if (protocolVersion != null) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
        if (properties.hasBearerToken()) {
            builder.header("Authorization", "Bearer " + properties.bearerToken());
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new UpstreamUnavailableException("Upstream timed out after " + properties.requestTimeout(), e);
        } catch (IOException e) {
            throw new UpstreamUnavailableException("Upstream unreachable (" + e.getClass().getSimpleName() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailableException("Interrupted while calling upstream", e);
        }
    }

    /**
     * Fails closed if upstream echoes our credential back. Runs on the raw bytes before anything is
     * parsed, hashed, audited, returned or formatted. The exception carries only the upstream host,
     * never the token or any part of the body.
     */
    private void rejectIfCredentialReflected(byte[] body) {
        if (!properties.hasBearerToken()) {
            return;
        }
        String token = properties.bearerToken();
        String text = new String(body, StandardCharsets.UTF_8);
        String jsonEscaped = new String(toBytes(mapper.getNodeFactory().textNode(token)), StandardCharsets.UTF_8);
        for (String form : new String[]{token, "Bearer " + token, jsonEscaped.substring(1, jsonEscaped.length() - 1)}) {
            if (text.contains(form)) {
                throw new UpstreamUnavailableException("Upstream " + properties.url().getHost()
                        + " reflected the configured credential; response discarded");
            }
        }
    }

    /** Picks the JSON-RPC response with the given id out of an SSE body ({@code data:} lines, blank-line separated). */
    private JsonNode findInEventStream(byte[] body, long id) {
        StringBuilder data = new StringBuilder();
        for (String line : (new String(body, StandardCharsets.UTF_8) + "\n\n").split("\\r?\\n")) {
            if (line.startsWith("data:")) {
                data.append(line.substring(5).strip()).append('\n');
            } else if (line.isEmpty() && !data.isEmpty()) {
                JsonNode event = parseChecked(data.toString().getBytes(StandardCharsets.UTF_8));
                if (event.path("id").asLong(-1) == id) {
                    return event;
                }
                data.setLength(0);
            }
        }
        return null;
    }

    /**
     * Parses one JSON message and re-runs the credential check on its decoded form, re-serialized
     * without the raw body's escapes, so a token hidden behind JSON unicode escapes is still found.
     * Every message (JSON reply or SSE event) is checked before it is retained or selected.
     */
    private JsonNode parseChecked(byte[] json) {
        JsonNode node = parse(json);
        rejectIfCredentialReflected(toBytes(node));
        return node;
    }

    private JsonNode parse(byte[] json) {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new UpstreamUnavailableException("Upstream returned invalid JSON", e);
        }
    }

    private byte[] toBytes(JsonNode node) {
        try {
            return mapper.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Exchange(byte[] body, JsonNode message, Instant receivedAt) {
    }

    private static final class SessionExpiredException extends RuntimeException {
        SessionExpiredException() {
            super(null, null, false, false);
        }
    }
}
