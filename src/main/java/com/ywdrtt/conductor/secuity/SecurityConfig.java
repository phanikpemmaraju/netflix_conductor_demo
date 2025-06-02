package com.ywdrtt.conductor.secuity;

package com.yourcompany.app.config;

import com.yourcompany.app.security.CustomPermissionEvaluator;
import com.yourcompany.app.security.JwtTokenConverterConfig;
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
@EnableMethodSecurity(prePostEnabled = true)
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
                        } else if (!rule.getMethods().isEmpty()) {
                            for (String method : rule.getMethods()) {
                                authorize.requestMatchers(new AntPathRequestMatcher(rule.getPath(), method))
                                        .hasAnyRole(rule.getRolesArray());
                            }
                        } else if (!rule.getRoles().isEmpty()) {
                            authorize.requestMatchers(pathMatcher).hasAnyRole(rule.getRolesArray());
                        } else {
                            authorize.requestMatchers(pathMatcher).authenticated();
                        }
                    }

                    authorize
                            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
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
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtTokenConverterConfig(securityRulesConfig).jwtGrantedAuthoritiesConverter());
        return converter;
    }
}
