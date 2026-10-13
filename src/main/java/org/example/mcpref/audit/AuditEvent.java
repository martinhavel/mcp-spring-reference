package org.example.mcpref.audit;

import java.util.List;
import java.util.Map;

import org.example.mcpref.governance.Outcome;

/**
 * One audit row: who, with which roles, called which tool, with which parameters, and what happened.
 *
 * @param resultSha256 SHA-256 of the raw upstream response; null when upstream was not contacted or gave no answer
 */
public record AuditEvent(
        String traceId,
        String principal,
        List<String> roles,
        String tool,
        Map<String, String> parameters,
        Outcome outcome,
        String resultSha256,
        String detail) {
}
