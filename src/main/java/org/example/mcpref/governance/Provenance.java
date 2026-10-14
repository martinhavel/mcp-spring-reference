package org.example.mcpref.governance;

import java.time.Instant;

/**
 * Where a result came from. {@code responseSha256} is the SHA-256 (hex) of the exact upstream
 * HTTP response body (for an SSE response: the selected event's data), which is returned
 * alongside as {@code rawResponse} so anyone can recompute it, and which is also stored in the
 * audit row.
 */
public record Provenance(
        String sourceUrl,
        String toolName,
        Instant fetchedAt,
        String responseSha256,
        String adapterVersion) {
}
