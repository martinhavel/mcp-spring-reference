package org.example.mcpref.governance;

/** Business roles known to the adapter. Mapped to Spring Security authorities {@code ROLE_<name>}. */
public enum Role {
    ANALYST,
    COMPLIANCE;

    public String authority() {
        return "ROLE_" + name();
    }
}
