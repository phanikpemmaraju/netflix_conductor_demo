package com.ywdrtt.conductor.working;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity; // RE-ADDED
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // RE-ADDED: Enables @PreAuthorize, @PostAuthorize, etc.
public class SecurityConfig {

    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final ConductorPermissionEvaluator conductorPermissionEvaluator;

    public SecurityConfig(JwtAuthenticationConverter jwtAuthenticationConverter,
                          ConductorPermissionEvaluator conductorPermissionEvaluator) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.conductorPermissionEvaluator = conductorPermissionEvaluator;
    }

    // RE-ADDED: Bean to register your custom PermissionEvaluator with the MethodSecurityExpressionHandler
    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        DefaultMethodSecurityExpressionHandler expressionHandler = new DefaultMethodSecurityExpressionHandler();
        expressionHandler.setPermissionEvaluator(conductorPermissionEvaluator); // Set your custom evaluator
        return expressionHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Disable CSRF for API-only applications
                .authorizeHttpRequests(auth -> auth
                        // Permit all requests to Swagger/OpenAPI documentation
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/webjars/**", "/swagger-resources/**").permitAll()
                        // Permit specific public endpoints (e.g., health check or public APIs)
                        .requestMatchers("/public/**").permitAll()
                        .requestMatchers("/health-check-resource").permitAll() // Explicitly permit, though handled by controller @PreAuthorize(permitAll)
                        // All other requests MUST be authenticated.
                        // Fine-grained authorization will be handled by @PreAuthorize on controller methods.
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))); // Plug in your custom converter

        return http.build();
    }
}
