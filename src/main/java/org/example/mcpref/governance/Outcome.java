package org.example.mcpref.governance;

import org.springframework.http.HttpStatus;

/** Every governed call ends in exactly one outcome, and every outcome is audited. */
public enum Outcome {
    /** Upstream answered; data and provenance are present. */
    SUCCESS(HttpStatus.OK),
    /** Upstream answered with a tool or protocol error; provenance is present, no business data. */
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY),
    /** Caller's role is not allowlisted for the tool. Upstream was not contacted. */
    DENIED(HttpStatus.FORBIDDEN),
    /** Parameter failed validation. Upstream was not contacted. */
    INVALID_INPUT(HttpStatus.BAD_REQUEST),
    /** Local upstream rate limit reached. Upstream was not contacted. */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    /** Upstream unreachable, timed out, or not offering the tool. No answer is fabricated. */
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus httpStatus;

    Outcome(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
