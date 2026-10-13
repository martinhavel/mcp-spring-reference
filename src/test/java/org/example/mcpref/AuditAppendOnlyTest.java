package org.example.mcpref;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AC5: the audit trail is append-only, enforced by database grants (not by application code). */
class AuditAppendOnlyTest extends IntegrationTestBase {

    private static final String INSERT = """
            INSERT INTO audit_event (trace_id, principal, roles, tool, parameters, outcome)
            VALUES ('ac5-probe', 'probe', 'ROLE_ANALYST', 'lookup_by_ico', '{"ico":"12345679"}', 'SUCCESS')
            """;

    @Test
    @DisplayName("AC5 the application role can INSERT audit rows")
    void appRoleCanInsert() throws SQLException {
        try (Connection c = connectAs("mcp_app", DB_APP_PASSWORD); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate(INSERT)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("AC5 the application role cannot SELECT, UPDATE, DELETE or TRUNCATE audit rows")
    void appRoleCannotReadOrModify() throws SQLException {
        try (Connection c = connectAs("mcp_app", DB_APP_PASSWORD)) {
            assertPermissionDenied(c, "SELECT * FROM audit_event");
            assertPermissionDenied(c, "UPDATE audit_event SET outcome = 'SUCCESS'");
            assertPermissionDenied(c, "DELETE FROM audit_event");
            assertPermissionDenied(c, "TRUNCATE audit_event");
        }
    }

    @Test
    @DisplayName("AC5 the application role cannot choose the timestamp or id of an audit row")
    void appRoleCannotBackdate() throws SQLException {
        try (Connection c = connectAs("mcp_app", DB_APP_PASSWORD)) {
            assertPermissionDenied(c, """
                    INSERT INTO audit_event (occurred_at, trace_id, principal, roles, tool, parameters, outcome)
                    VALUES ('2000-01-01T00:00:00Z', 'ac5-backdate', 'probe', 'ROLE_ANALYST', 'lookup_by_ico', '{}', 'SUCCESS')
                    """);
        }
    }

    @Test
    @DisplayName("AC5 the application role cannot alter the schema or drop the audit table")
    void appRoleCannotChangeSchema() throws SQLException {
        try (Connection c = connectAs("mcp_app", DB_APP_PASSWORD)) {
            assertPermissionDenied(c, "DROP TABLE audit_event");
            assertPermissionDenied(c, "ALTER TABLE audit_event DISABLE TRIGGER ALL");
            assertPermissionDenied(c, "CREATE TABLE mcpref.shadow (id int)");
        }
    }

    @Test
    @DisplayName("AC5 even the schema owner cannot UPDATE or DELETE audit rows (trigger)")
    void ownerBlockedByTrigger() throws SQLException {
        try (Connection c = connectAs("mcp_owner", DB_OWNER_PASSWORD); Statement s = c.createStatement()) {
            s.executeUpdate(INSERT);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE audit_event SET outcome = 'DENIED'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM audit_event"))
                    .hasMessageContaining("append-only");
        }
    }

    private static void assertPermissionDenied(Connection c, String sql) {
        assertThatThrownBy(() -> {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }).isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState())
                        .as("SQLSTATE for: %s", sql)
                        .isIn("42501", "42P01")); // insufficient_privilege (or table not visible)
    }
}
