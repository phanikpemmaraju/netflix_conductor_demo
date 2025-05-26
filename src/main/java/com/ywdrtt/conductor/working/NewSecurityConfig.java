package com.ywdrtt.conductor.working;

package com.netflix.conductor.security; // Adjust package as needed

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import static com.netflix.conductor.rest.config.RequestMappingConstants.*; // Import your constants

@Configuration
@EnableWebSecurity
public class NewSecurityConfig {

    // Inject the JwtAuthenticationConverter bean provided by JwtConverterConfig
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    public SecurityConfig(JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Consider enabling for browser-based clients if applicable
                .authorizeHttpRequests(authorize -> authorize
                        // Order matters: More specific paths first

                        // 1. Admin Resource: /admin/** (Full access for ADMIN)
                        .requestMatchers(ADMIN + "/**").hasRole("ADMIN")

                        // 2. Queue Admin Resource: /queue/admin/** (GET/POST for ADMIN)
                        .requestMatchers(HttpMethod.GET, QUEUE_ADMIN + "/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, QUEUE_ADMIN + "/**").hasRole("ADMIN")

                        // 3. Workflow Bulk Resource: /workflow/bulk/** (All methods for WORKFLOW_MANAGER, ADMIN)
                        // Note: ADMIN will also have WORKFLOW_MANAGER role due to role hierarchy/assignment in your IdP if configured.
                        // Otherwise, you might need to explicitly list ADMIN for all these if ADMIN doesn't inherit WORKFLOW_MANAGER role.
                        .requestMatchers(WORKFLOW_BULK + "/**").hasAnyRole("WORKFLOW_MANAGER", "ADMIN")

                        // 4. Workflow Resource: /workflow/**
                        //    GET/DELETE: USER, WORKFLOW_MANAGER, ADMIN
                        .requestMatchers(HttpMethod.GET, WORKFLOW + "/**").hasAnyRole("USER", "WORKFLOW_MANAGER", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, WORKFLOW + "/**").hasAnyRole("USER", "WORKFLOW_MANAGER", "ADMIN")
                        //    POST/PUT: WORKFLOW_MANAGER, ADMIN
                        .requestMatchers(HttpMethod.POST, WORKFLOW + "/**").hasAnyRole("WORKFLOW_MANAGER", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, WORKFLOW + "/**").hasAnyRole("WORKFLOW_MANAGER", "ADMIN")

                        // 5. Metadata Resource: /metadata/** (All methods for USER, METADATA_MANAGER, ADMIN)
                        .requestMatchers(METADATA + "/**").hasAnyRole("USER", "METADATA_MANAGER", "ADMIN")

                        // 6. Event Resource: /event/** (GET/POST/PUT for USER, ADMIN)
                        .requestMatchers(HttpMethod.GET, EVENT + "/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, EVENT + "/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, EVENT + "/**").hasAnyRole("USER", "ADMIN")

                        // 7. Task Resource: /task/** (GET/PUT for USER, ADMIN)
                        .requestMatchers(HttpMethod.GET, TASK + "/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, TASK + "/**").hasAnyRole("USER", "ADMIN")

                        // 8. Health Check Resource: /health (GET for USER, ADMIN)
                        .requestMatchers(HttpMethod.GET, HEALTH_CHECK + "/**").hasAnyRole("USER", "ADMIN")

                        // All other requests must be authenticated
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)) // Use the injected bean
                );

        return http.build();
    }
}
