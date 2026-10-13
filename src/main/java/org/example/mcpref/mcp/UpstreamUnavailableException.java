package org.example.mcpref.mcp;

/** Upstream gave no usable answer. Mapped to outcome UNAVAILABLE; never replaced by a guess. */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message) {
        super(message);
    }

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
