package org.example.mcpref.audit;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Appends audit rows. The application's database role holds column-level INSERT on
 * {@code audit_event} and nothing else (see {@code V2__grants.sql}), so this class could not
 * read, update or delete audit rows even if it tried. {@code id} and {@code occurred_at} are set
 * by the database, so the application cannot backdate an entry.
 *
 * <p>If the insert fails the exception propagates and the request fails: no result leaves the
 * service without an audit record (fail closed).
 */
@Component
public class AuditWriter {

    private static final String INSERT = """
            INSERT INTO audit_event (trace_id, principal, roles, tool, parameters, outcome, result_sha256, detail)
            VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void append(AuditEvent event) {
        jdbc.update(INSERT,
                event.traceId(),
                event.principal(),
                String.join(",", event.roles()),
                event.tool(),
                toJson(event.parameters()),
                event.outcome().name(),
                event.resultSha256(),
                truncate(event.detail(), 512));
    }

    private String toJson(Map<String, String> parameters) {
        try {
            return mapper.writeValueAsString(parameters);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
