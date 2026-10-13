package org.example.mcpref.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * Fixed-window limiter for upstream tool calls (one window per minute, shared by all users).
 * Intentionally simple; a production deployment would typically use a distributed limiter
 * (e.g. Bucket4j + Redis) or an API gateway policy.
 */
@Component
public class UpstreamRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final int maxCallsPerWindow;
    private Instant windowStart;
    private int callsInWindow;

    public UpstreamRateLimiter(Clock clock, McpProperties properties) {
        this.clock = clock;
        this.maxCallsPerWindow = properties.maxCallsPerMinute();
        this.windowStart = clock.instant();
    }

    public synchronized boolean tryAcquire() {
        Instant now = clock.instant();
        if (!now.isBefore(windowStart.plus(WINDOW))) {
            windowStart = now;
            callsInWindow = 0;
        }
        if (callsInWindow >= maxCallsPerWindow) {
            return false;
        }
        callsInWindow++;
        return true;
    }
}
