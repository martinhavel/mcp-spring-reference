package org.example.mcpref.mcp;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An upstream answer exactly as received.
 *
 * @param body      the raw HTTP response body (hashed for provenance)
 * @param payload   the JSON-RPC {@code result} object, or the {@code error} object if {@code error} is true
 * @param error     true for a JSON-RPC error or a tool result with {@code isError: true}
 * @param fetchedAt when the response was received (UTC)
 */
public record RawToolResponse(byte[] body, JsonNode payload, boolean error, Instant fetchedAt) {
}
