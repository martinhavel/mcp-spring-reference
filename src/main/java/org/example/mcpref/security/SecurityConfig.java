package org.example.mcpref.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Authentication only. Authorization of tool calls is done by {@code GovernedTool} inside the
 * service, so that denials are audited with the full context.
 *
 * <p>Demo setup: HTTP Basic with users from configuration (passwords from environment
 * variables). In a real deployment replace this with an OAuth2 resource server (JWT from the
 * corporate IdP) and map IdP groups to {@code ROLE_ANALYST} / {@code ROLE_COMPLIANCE}.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Stateless, credentials sent per request, GET-only API: no session cookie to ride on.
                .csrf(csrf -> csrf.disable())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(DemoUsers demoUsers, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(demoUsers.users().stream()
                .map(u -> User.withUsername(u.username())
                        .password(encoder.encode(u.password()))
                        .roles(u.roles().toArray(String[]::new))
                        .build())
                .toList());
    }

    @ConfigurationProperties("app.security")
    public record DemoUsers(List<DemoUser> users) {

        public record DemoUser(String username, String password, List<String> roles) {
            @Override
            public String toString() {
                return "DemoUser[username=" + username + ", roles=" + roles + "]";
            }
        }
    }
}
