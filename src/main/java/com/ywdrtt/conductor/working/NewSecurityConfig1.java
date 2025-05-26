package com.ywdrtt.conductor.working;

package com.netflix.conductor.security;

import com.yourcompany.yourconductorapp.config.ConductorRoleConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Arrays;
import java.util.stream.Stream;

import static com.netflix.conductor.rest.config.RequestMappingConstants.*;

@Configuration
@EnableWebSecurity
public class NewSecurityConfig1 {

    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final ConductorRoleConfig conductorRoleConfig;

    public SecurityConfig(JwtAuthenticationConverter jwtAuthenticationConverter,
                          ConductorRoleConfig conductorRoleConfig) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.conductorRoleConfig = conductorRoleConfig;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        // 1. Admin Resource: /admin/** (Full access for ADMIN)
                        // Direct use of String[] from config
                        .requestMatchers(ADMIN + "/**").hasAnyRole(conductorRoleConfig.adminRoles())

                        // 2. Queue Admin Resource: /queue/admin/** (GET/POST for ADMIN)
                        .requestMatchers(HttpMethod.GET, QUEUE_ADMIN + "/**").hasAnyRole(conductorRoleConfig.adminRoles())
                        .requestMatchers(HttpMethod.POST, QUEUE_ADMIN + "/**").hasAnyRole(conductorRoleConfig.adminRoles())

                        // 3. Workflow Bulk Resource: /workflow/bulk/** (All methods for WORKFLOW_MANAGER, ADMIN)
                        .requestMatchers(WORKFLOW_BULK + "/**").hasAnyRole(
                                Stream.concat(
                                        Arrays.stream(conductorRoleConfig.workflowManagerRoles()),
                                        Arrays.stream(conductorRoleConfig.adminRoles())
                                ).toArray(String[]::new)
                        )

                        // 4. Workflow Resource: /workflow/**
                        //    GET/DELETE: USER, WORKFLOW_MANAGER, ADMIN
                        .requestMatchers(HttpMethod.GET, WORKFLOW + "/**").hasAnyRole(
                                Stream.of(
                                        conductorRoleConfig.userRoles(),
                                        conductorRoleConfig.workflowManagerRoles(),
                                        conductorRoleConfig.adminRoles()
                                ).flatMap(Arrays::stream).toArray(String[]::new)
                        )
                        .requestMatchers(HttpMethod.DELETE, WORKFLOW + "/**").hasAnyRole(
                                Stream.of(
                                        conductorRoleConfig.userRoles(),
                                        conductorRoleConfig.workflowManagerRoles(),
                                        conductorRoleConfig.adminRoles()
                                ).flatMap(Arrays::stream).toArray(String[]::new)
                        )
                        //    POST/PUT: WORKFLOW_MANAGER, ADMIN
                        .requestMatchers(HttpMethod.POST, WORKFLOW + "/**").hasAnyRole(
                                Stream.concat(
                                        Arrays.stream(conductorRoleConfig.workflowManagerRoles()),
                                        Arrays.stream(conductorRoleConfig.adminRoles())
                                ).toArray(String[]::new)
                        )
                        .requestMatchers(HttpMethod.PUT, WORKFLOW + "/**").hasAnyRole(
                                Stream.concat(
                                        Arrays.stream(conductorRoleConfig.workflowManagerRoles()),
                                        Arrays.stream(conductorRoleConfig.adminRoles())
                                ).toArray(String[]::new)
                        )

                        // 5. Metadata Resource: /metadata/** (All methods for USER, METADATA_MANAGER, ADMIN)
                        .requestMatchers(METADATA + "/**").hasAnyRole(
                                Stream.of(
                                        conductorRoleConfig.userRoles(),
                                        conductorRoleConfig.metadataManagerRoles(),
                                        conductorRoleConfig.adminRoles()
                                ).flatMap(Arrays::stream).toArray(String[]::new)
                        )

                        // 6. Event Resource: /event/** (GET/POST/PUT for USER, ADMIN)
                        .requestMatchers(HttpMethod.GET, EVENT + "/**").hasAnyRole(
                                Stream.concat(
                                        Arrays.stream(conductorRoleConfig.userRoles()),
                                        Arrays.stream(conductorRoleConfig.adminRoles())
                                ).toArray(String[]::new)
                        )
                        .requestMatchers(HttpMethod.POST, EVENT + "/**").hasAnyRole(
                                Stream.concat(
                                        Arrays.stream(conductorRoleConfig.userRoles()),
                                        Arrays.stream(conductorRoleConfig.adminRoles())
                                ).toArray(String[]::new)
                        )
                        .requestMatchers(HttpMethod.PUT, EVENT + "/**").hasAnyRole(
                                Stream.concat(
                                        Arrays.stream(conductorRoleConfig.userRoles()),
                                        Arrays.stream(conductorRoleConfig.adminRoles())
                                ).toArray(String[]::new)
                        )

                        // 7. Task Resource: /task/** (GET/PUT for USER, ADMIN)
                        .requestMatchers(HttpMethod.GET, TASK + "/**").hasAnyRole(
                                Stream.concat(
                                        Arrays.stream(conductorRoleConfig.userRoles()),
                                        Arrays.stream(conductorRoleConfig.adminRoles())
                                ).toArray(String[]::new)
                        )
                        .requestMatchers(HttpMethod.PUT, TASK + "/**").hasAnyRole(
                                Stream.concat(
                                        Arrays.stream(conductorRoleConfig.userRoles()),
                                        Arrays.stream(conductorRoleConfig.adminRoles())
                                ).toArray(String[]::new)
                        )

                        // 8. Health Check Resource: /health (GET for USER, ADMIN)
                        .requestMatchers(HttpMethod.GET, HEALTH_CHECK + "/**").hasAnyRole(
                                Stream.concat(
                                        Arrays.stream(conductorRoleConfig.userRoles()),
                                        Arrays.stream(conductorRoleConfig.adminRoles())
                                ).toArray(String[]::new)
                        )

                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                );

        return http.build();
    }
}