package com.yourcompany.app.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true) // Enable @PreAuthorize and @PostAuthorize
public class SecurityConfig {

    private final SecurityRulesConfig securityRulesConfig;
    private final CustomPermissionEvaluator customPermissionEvaluator;

    public SecurityConfig(SecurityRulesConfig securityRulesConfig, CustomPermissionEvaluator customPermissionEvaluator) {
        this.securityRulesConfig = securityRulesConfig;
        this.customPermissionEvaluator = customPermissionEvaluator;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    for (SecurityRulesConfig.EndpointRule rule : securityRulesConfig.getRules()) {
                        AntPathRequestMatcher pathMatcher = new AntPathRequestMatcher(rule.getPath());

                        if (rule.isPermitAll()) {
                            authorize.requestMatchers(pathMatcher).permitAll();
                        } else if (rule.isAuthenticated()) {
                            authorize.requestMatchers(pathMatcher).authenticated();
                        } else if (!rule.getMethods().isEmpty()) {
                            for (String method : rule.getMethods()) {
                                authorize.requestMatchers(new AntPathRequestMatcher(rule.getPath(), method))
                                        .hasAnyRole(rule.getRolesArray());
                            }
                        } else if (!rule.getRoles().isEmpty()) {
                            authorize.requestMatchers(pathMatcher).hasAnyRole(rule.getRolesArray());
                        } else {
                            // If a path is defined but has no permitAll, authenticated, or roles specified, default to authenticated
                            authorize.requestMatchers(pathMatcher).authenticated();
                        }
                    }

                    // Always allow OPTIONS requests for CORS pre-flight
                    authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            // Any other request not explicitly covered by the rules must be authenticated
                            .anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(customPermissionEvaluator);
        return handler;
    }

    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        // This is crucial for mapping external JWT roles (e.g., microtx_admin_role)
        // to Spring Security internal roles (e.g., "ADMIN" or "ROLE_ADMIN").
        // Ensure your JwtTokenConverterConfig maps these correctly.
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtTokenConverterConfig(securityRulesConfig).jwtGrantedAuthoritiesConverter());
        return converter;
    }
}