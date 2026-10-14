package org.example.mcpref.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class UpstreamRateLimiterTest {

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    @Test
    void allowsUpToLimitPerMinuteThenResets() {
        var limiter = new UpstreamRateLimiter(clock,
                new McpProperties(URI.create("http://localhost/mcp"), null, Duration.ofSeconds(1), Duration.ofSeconds(1), 2));

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        now = now.plusSeconds(60);
        assertThat(limiter.tryAcquire()).isTrue();
    }
}
