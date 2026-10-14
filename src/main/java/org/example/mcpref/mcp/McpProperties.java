package org.example.mcpref.mcp;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upstream MCP server settings.
 *
 * @param url                 MCP streamable-HTTP endpoint
 * @param bearerToken         optional upstream credential; only ever placed in the outbound
 *                            {@code Authorization} header, never in results, audit or logs
 * @param connectTimeout      TCP connect timeout
 * @param requestTimeout      timeout per HTTP request to upstream
 * @param maxCallsPerMinute   local rate limit on tool calls (the public ARES server allows 60/min per IP)
 */
@ConfigurationProperties("mcp.upstream")
public record McpProperties(
        URI url,
        String bearerToken,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxCallsPerMinute) {

    public boolean hasBearerToken() {
        return bearerToken != null && !bearerToken.isBlank();
    }

    @Override
    public String toString() {
        return "McpProperties[url=" + url + ", bearerToken=" + (hasBearerToken() ? "***" : "<none>")
                + ", connectTimeout=" + connectTimeout + ", requestTimeout=" + requestTimeout
                + ", maxCallsPerMinute=" + maxCallsPerMinute + "]";
    }
}
