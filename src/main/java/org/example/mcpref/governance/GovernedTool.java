package org.example.mcpref.governance;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;

/**
 * The complete allowlist of upstream MCP tools this application may call, and which roles may
 * call each one.
 *
 * <p>This enum is the authorization policy. It is compiled Java, not configuration and not a
 * prompt: adding a tool or widening a role requires a code change and a review. Tool names were
 * taken from the ARES server's {@code tools/list}; the client re-checks at runtime that the
 * upstream still offers them and fails closed if not.
 *
 * <p>Deliberately absent: {@code search_companies}, {@code search_by_address},
 * {@code search_by_nace} (free-text search), {@code get_statutaries} and {@code get_history}
 * (personal data of natural persons), {@code get_bank_accounts}.
 */
public enum GovernedTool {

    COMPANY_LOOKUP("lookup_by_ico", EnumSet.of(Role.ANALYST, Role.COMPLIANCE)),
    VAT_CHECK("check_vat_payer", EnumSet.of(Role.COMPLIANCE));

    /** The single argument name both tools accept. */
    public static final String ICO_ARGUMENT = "ico";

    private final String mcpName;
    private final Set<Role> allowedRoles;

    GovernedTool(String mcpName, Set<Role> allowedRoles) {
        this.mcpName = mcpName;
        this.allowedRoles = allowedRoles;
    }

    public String mcpName() {
        return mcpName;
    }

    public boolean isAllowedFor(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> allowedRoles.stream().anyMatch(r -> r.authority().equals(a)));
    }

    public static Set<String> mcpNames() {
        return Arrays.stream(values()).map(GovernedTool::mcpName).collect(Collectors.toUnmodifiableSet());
    }
}
