package org.example.mcpref.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.example.mcpref.audit.AuditEvent;
import org.example.mcpref.audit.AuditWriter;
import org.example.mcpref.format.VerifiedResultFormatter;
import org.example.mcpref.ico.Ico;
import org.example.mcpref.ico.InvalidIcoException;
import org.example.mcpref.mcp.McpClient;
import org.example.mcpref.mcp.McpProperties;
import org.example.mcpref.mcp.RawToolResponse;
import org.example.mcpref.mcp.UpstreamRateLimiter;
import org.example.mcpref.mcp.UpstreamUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * The governed call pipeline. Order matters and each gate is audited:
 * <ol>
 *   <li>authorize (role allowlist in {@link GovernedTool})</li>
 *   <li>validate the parameter ({@link Ico}, MOD11)</li>
 *   <li>rate limit</li>
 *   <li>call upstream (with timeout)</li>
 *   <li>attach provenance</li>
 *   <li>append audit (fail closed)</li>
 *   <li>optionally format (best effort, never authoritative)</li>
 * </ol>
 */
@Service
public class GovernedToolService {

    private static final int MAX_AUDITED_INPUT_LENGTH = 32;
    private static final Logger log = LoggerFactory.getLogger(GovernedToolService.class);

    private final McpClient mcpClient;
    private final UpstreamRateLimiter rateLimiter;
    private final AuditWriter auditWriter;
    private final ObjectProvider<VerifiedResultFormatter> formatter;
    private final String sourceUrl;
    private final String adapterVersion;

    public GovernedToolService(McpClient mcpClient, UpstreamRateLimiter rateLimiter, AuditWriter auditWriter,
                               ObjectProvider<VerifiedResultFormatter> formatter, McpProperties properties,
                               @Value("${app.adapter-version}") String adapterVersion) {
        this.mcpClient = mcpClient;
        this.rateLimiter = rateLimiter;
        this.auditWriter = auditWriter;
        this.formatter = formatter;
        this.sourceUrl = properties.url().toString();
        this.adapterVersion = adapterVersion;
    }

    public ToolResult execute(GovernedTool tool, String rawIco, Authentication user, String traceId) {
        Caller caller = Caller.of(user);
        Map<String, String> requested = Map.of(GovernedTool.ICO_ARGUMENT, truncate(rawIco));

        if (!tool.isAllowedFor(user.getAuthorities())) {
            audit(traceId, caller, tool, requested, Outcome.DENIED, null, "role not allowlisted for tool");
            return ToolResult.withoutData(traceId, tool, Outcome.DENIED, null, "Your role may not call this tool");
        }

        Ico ico;
        try {
            ico = Ico.parse(rawIco);
        } catch (InvalidIcoException e) {
            audit(traceId, caller, tool, requested, Outcome.INVALID_INPUT, null, e.getMessage());
            return ToolResult.withoutData(traceId, tool, Outcome.INVALID_INPUT, null, e.getMessage());
        }
        Map<String, String> parameters = Map.of(GovernedTool.ICO_ARGUMENT, ico.value());

        if (!rateLimiter.tryAcquire()) {
            audit(traceId, caller, tool, parameters, Outcome.RATE_LIMITED, null, "local upstream rate limit reached");
            return ToolResult.withoutData(traceId, tool, Outcome.RATE_LIMITED, ico.value(), "Upstream rate limit reached, retry later");
        }

        RawToolResponse response;
        try {
            response = mcpClient.callTool(tool, ico, traceId);
        } catch (UpstreamUnavailableException e) {
            log.warn("Upstream unavailable for {}: {}", tool.mcpName(), e.getMessage());
            audit(traceId, caller, tool, parameters, Outcome.UNAVAILABLE, null, e.getMessage());
            return ToolResult.withoutData(traceId, tool, Outcome.UNAVAILABLE, ico.value(),
                    "Upstream registry unavailable; no data returned");
        }

        String sha256 = sha256Hex(response.body());
        Provenance provenance = new Provenance(sourceUrl, tool.mcpName(), response.fetchedAt(), sha256, adapterVersion);
        Outcome outcome = response.error() ? Outcome.UPSTREAM_ERROR : Outcome.SUCCESS;
        audit(traceId, caller, tool, parameters, outcome, sha256, response.error() ? "upstream returned an error" : null);

        ToolResult result = new ToolResult(traceId, tool.mcpName(), outcome, ico.value(),
                response.error() ? "Upstream returned an error" : null,
                response.payload(), new String(response.body(), StandardCharsets.UTF_8), provenance, null);
        return format(result);
    }

    private ToolResult format(ToolResult result) {
        VerifiedResultFormatter f = formatter.getIfAvailable();
        if (f == null || result.outcome() != Outcome.SUCCESS) {
            return result;
        }
        try {
            return result.withSummary(f.format(result));
        } catch (RuntimeException e) {
            log.warn("Formatter failed; returning result without summary", e);
            return result;
        }
    }

    private void audit(String traceId, Caller caller, GovernedTool tool, Map<String, String> parameters,
                       Outcome outcome, String sha256, String detail) {
        auditWriter.append(new AuditEvent(traceId, caller.name(), caller.roles(), tool.mcpName(),
                parameters, outcome, sha256, detail));
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Rejected input is audited, but bounded so the audit log cannot be flooded. */
    private static String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= MAX_AUDITED_INPUT_LENGTH ? raw : raw.substring(0, MAX_AUDITED_INPUT_LENGTH);
    }

    private record Caller(String name, List<String> roles) {
        static Caller of(Authentication user) {
            return new Caller(user.getName(),
                    user.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList());
        }
    }
}
