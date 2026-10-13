-- Least privilege for the application role (${app_role}).
-- It may insert audit rows and nothing else: no SELECT, UPDATE, DELETE or TRUNCATE.
-- Column-level INSERT excludes id and occurred_at, so timestamps come from the database clock.
REVOKE ALL ON audit_event FROM PUBLIC;
GRANT USAGE ON SCHEMA mcpref TO ${app_role};
GRANT INSERT (trace_id, principal, roles, tool, parameters, outcome, result_sha256, detail)
    ON audit_event TO ${app_role};

-- Read access for auditors is a separate role, never the application.
GRANT USAGE ON SCHEMA mcpref TO mcp_auditor;
GRANT SELECT ON audit_event TO mcp_auditor;
