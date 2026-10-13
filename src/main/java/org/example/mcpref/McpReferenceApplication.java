package org.example.mcpref;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpReferenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpReferenceApplication.class, args);
    }

    /** All provenance and audit timestamps are UTC. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
