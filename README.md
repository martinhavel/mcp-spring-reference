# MCP as a governed integration adapter in a Spring application

Reference implementation: role-bound MCP tools, parameterised queries, provenance on every result, append-only audit.

Flow: user → Spring service (Spring Security) → permission-bound MCP tool (client to the public ARES MCP server) → validated parameter (IČO) → result with provenance → audit (PostgreSQL, insert-only).

Status: in progress.
