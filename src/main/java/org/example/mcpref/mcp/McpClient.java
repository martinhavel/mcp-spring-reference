package org.example.mcpref.mcp;

import org.example.mcpref.governance.GovernedTool;
import org.example.mcpref.ico.Ico;

/**
 * Narrow, read-only port to the upstream MCP server.
 *
 * <p>The signature is the policy: callers can only pass an allowlisted {@link GovernedTool} and a
 * validated {@link Ico}. There is no generic "call any tool with any arguments" method.
 */
public interface McpClient {

    /**
     * @throws UpstreamUnavailableException on timeout, transport failure, unexpected HTTP status,
     *                                      or when upstream no longer offers the tool
     */
    RawToolResponse callTool(GovernedTool tool, Ico ico, String traceId);
}
