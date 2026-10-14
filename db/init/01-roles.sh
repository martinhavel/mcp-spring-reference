#!/bin/sh
# Database roles for the reference implementation. Runs once, as the postgres superuser,
# from /docker-entrypoint-initdb.d (docker-compose and Testcontainers use the same script).
#
#   mcp_owner   - owns schema mcpref, runs Flyway migrations, issues grants
#   mcp_app     - the Spring application at runtime; privileges come only from V2__grants.sql
#   mcp_auditor - read-only access to the audit trail (NOLOGIN; grant it to real auditor users)
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE mcp_owner LOGIN PASSWORD '${MCP_OWNER_PASSWORD}';
    CREATE ROLE mcp_app LOGIN PASSWORD '${MCP_APP_PASSWORD}';
    CREATE ROLE mcp_auditor NOLOGIN;

    REVOKE ALL ON DATABASE "${POSTGRES_DB}" FROM PUBLIC;
    GRANT CONNECT ON DATABASE "${POSTGRES_DB}" TO mcp_owner, mcp_app;
    REVOKE CREATE ON SCHEMA public FROM PUBLIC;

    CREATE SCHEMA mcpref AUTHORIZATION mcp_owner;
    REVOKE ALL ON SCHEMA mcpref FROM PUBLIC;
EOSQL
