package org.example.mcpref.governance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The response of a governed tool call. Contains only verified data: what upstream returned,
 * how to verify it, and what happened. It never contains credentials.
 *
 * @param data        the MCP {@code result} (or JSON-RPC {@code error}) object; null unless upstream answered
 * @param rawResponse the exact upstream response body; SHA-256 of its UTF-8 bytes equals {@code provenance.responseSha256}
 * @param summary     optional human-readable rendering by a {@code VerifiedResultFormatter}; never authoritative
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(
        String traceId,
        String tool,
        Outcome outcome,
        String ico,
        String message,
        JsonNode data,
        String rawResponse,
        Provenance provenance,
        String summary) {

    public static ToolResult withoutData(String traceId, GovernedTool tool, Outcome outcome, String ico, String message) {
        return new ToolResult(traceId, tool.mcpName(), outcome, ico, message, null, null, null, null);
    }

    public ToolResult withSummary(String summary) {
        return new ToolResult(traceId, tool, outcome, ico, message, data, rawResponse, provenance, summary);
    }
}
