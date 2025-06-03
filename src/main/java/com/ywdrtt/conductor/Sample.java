// src/main/java/com/netflix/conductor/security/config/EndpointRule.java
package com.netflix.conductor.security.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single security rule defined in application.properties.
 * This class is used by SecurityRulesConfig to bind properties.
 */
public class EndpointRule {
    private String path;
    private List<String> methods = new ArrayList<>(); // e.g., ["GET", "POST"]
    private List<String> roles = new ArrayList<>();   // e.g., ["ADMIN", "READ_ONLY_USER"]

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public List<String> getMethods() {
        return methods;
    }

    public void setMethods(List<String> methods) {
        this.methods = methods;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void voidsetRoles(List<String> roles) {
        this.roles = roles;
    }

    @Override
    public String toString() {
        return "EndpointRule{" +
                "path='" + path + '\'' +
                ", methods=" + methods +
                ", roles=" + roles +
                '}';
    }
}
```java
// src/main/java/com/netflix/conductor/security/config/SecurityRulesConfig.java
package com.netflix.conductor.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ConfigurationProperties bean to read security rules from application.properties.
 * Properties are prefixed with "conductor.security".
 *
 * Example properties:
 * conductor.security.rules[0].path=/api/admin/**
 * conductor.security.rules[0].roles=${conductor.security.role.admin}
 * conductor.security.rules[0].methods=GET,POST,PUT,DELETE
 *
 * conductor.security.role.admin=ADMIN
 * conductor.security.role.read_only_user=READ_ONLY_USER
 */
@Component
@ConfigurationProperties(prefix = "conductor.security")
public class SecurityRulesConfig {

    private List<EndpointRule> rules = new ArrayList<>();
    private Map<String, String> role = new HashMap<>(); // Maps property keys to actual role strings

    // Claims is not directly used for this permission evaluation, but kept as per your original structure.
    private Claims claims = new Claims();

    public List<EndpointRule> getRules() {
        return rules;
    }

    public void setRules(List<EndpointRule> rules) {
        this.rules = rules;
    }

    public Map<String, String> getRole() {
        return role;
    }

    public void setRole(Map<String, String> role) {
        this.role = role;
    }

    public Claims getClaims() {
        return claims;
    }

    public void setClaims(Claims claims) {
        this.claims = claims;
    }

    // Nested Claims class (if it's part of this file)
    public static class Claims {
        // Define properties for claims if any, e.g.,
        // private String defaultClaim;
        // public String getDefaultClaim() { return defaultClaim; }
        // public void setDefaultClaim(String defaultClaim) { this.defaultClaim = defaultClaim; }
    }
}
```java
// src/main/java/com/netflix/conductor/security/evaluator/ConductorPermissionEvaluator.java
package com.netflix.conductor.security.evaluator;

import com.netflix.conductor.security.config.ConductorPermission;
import com.netflix.conductor.security.config.EndpointRule;
import com.netflix.conductor.security.config.SecurityRulesConfig;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Custom PermissionEvaluator for Spring Security's @PreAuthorize annotation.
 * This evaluator checks if a user has the necessary roles to perform a specific
 * ConductorPermission (READ, CREATE, UPDATE, DELETE) on a given resource path.
 */
@Component
public class ConductorPermissionEvaluator implements PermissionEvaluator {

    private final SecurityRulesConfig securityRulesConfig;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public ConductorPermissionEvaluator(SecurityRulesConfig securityRulesConfig) {
        this.securityRulesConfig = securityRulesConfig;
    }

    /**
     * Checks if the authenticated user has permission to access a specific object.
     * This method is called by @PreAuthorize("hasPermission(#object, #permission)")
     *
     * @param authentication The current user's authentication object.
     * @param targetDomainObject The object being secured (in our case, the API path as a String).
     * @param permission The permission required (e.g., "READ", "CREATE", "UPDATE", "DELETE").
     * @return true if the user has permission, false otherwise.
     */
    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false; // Not authenticated
        }

        if (!(targetDomainObject instanceof String)) {
            // The targetDomainObject should be the API path as a String
            return false;
        }

        String apiPath = (String) targetDomainObject;
        ConductorPermission requiredPermission;

        if (permission instanceof String) {
            try {
                requiredPermission = ConductorPermission.valueOf(((String) permission).toUpperCase());
            } catch (IllegalArgumentException e) {
                // Invalid permission string
                return false;
            }
        } else if (permission instanceof ConductorPermission) {
            requiredPermission = (ConductorPermission) permission;
        } else {
            // Unsupported permission type
            return false;
        }

        // Get the roles/authorities of the authenticated user
        Set<String> userRoles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                // Assuming roles are prefixed with "ROLE_", remove it for comparison with config roles
                .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
                .collect(Collectors.toSet());

        // Log user roles for debugging
        System.out.println("Checking permission for path: " + apiPath + ", required permission: " + requiredPermission + ", User roles: " + userRoles);

        // Iterate through the configured security rules
        for (EndpointRule rule : securityRulesConfig.getRules()) {
            // Check if the API path matches the rule's path pattern
            if (antPathMatcher.match(rule.getPath(), apiPath)) {
                // Convert rule's HTTP methods to ConductorPermissions
                Set<ConductorPermission> rulePermissions = ConductorPermission.fromHttpMethods(rule.getMethods());

                // Check if the rule's permissions include the required permission
                if (rulePermissions.contains(requiredPermission)) {
                    // Check if any of the user's roles match the roles defined in this rule
                    Set<String> allowedRolesForRule = new HashSet<>();
                    for (String configuredRoleKey : rule.getRoles()) {
                        // Resolve the actual role string from the role map
                        String actualRole = securityRulesConfig.getRole().getOrDefault(configuredRoleKey, configuredRoleKey);
                        allowedRolesForRule.add(actualRole);
                    }

                    // Check for intersection of user roles and allowed roles for this rule
                    boolean hasMatchingRole = userRoles.stream()
                            .anyMatch(allowedRolesForRule::contains);

                    if (hasMatchingRole) {
                        System.out.println("Permission GRANTED for path: " + apiPath + ", permission: " + requiredPermission + ", by rule: " + rule.getPath());
                        return true; // User has permission based on this rule
                    }
                }
            }
        }

        System.out.println("Permission DENIED for path: " + apiPath + ", permission: " + requiredPermission);
        return false; // No matching rule found or user doesn't have required roles
    }

    /**
     * This overloaded method is typically used when the target object is an instance
     * of a domain object with an ID. Not used in this specific scenario where
     * targetDomainObject is a String path.
     */
    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        // Not used in this implementation, as we are securing based on path string.
        return false;
    }
}
```java
// src/main/java/com/netflix/conductor/security/config/SecurityConfig.java
package com.netflix.conductor.security.config;

import com.netflix.conductor.security.evaluator.ConductorPermissionEvaluator;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.context.annotation.Bean;

/**
 * Spring Security configuration to enable method security and register the custom PermissionEvaluator.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // Enables @PreAuthorize, @PostAuthorize, etc.
public class SecurityConfig {

    private final ConductorPermissionEvaluator conductorPermissionEvaluator;

    public SecurityConfig(ConductorPermissionEvaluator conductorPermissionEvaluator) {
        this.conductorPermissionEvaluator = conductorPermissionEvaluator;
    }

    /**
     * Configures the method security expression handler to use our custom PermissionEvaluator.
     * This is crucial for @PreAuthorize("hasPermission(...)") to work.
     */
    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        DefaultMethodSecurityExpressionHandler expressionHandler = new DefaultMethodSecurityExpressionHandler();
        expressionHandler.setPermissionEvaluator(conductorPermissionEvaluator);
        return expressionHandler;
    }

    /**
     * Configures the HTTP security filter chain.
     * You might already have this configured in your application.
     * This example disables CSRF for simplicity and allows all requests for demonstration,
     * as method security will handle the fine-grained authorization.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Disable CSRF for simpler API testing
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().authenticated() // All requests require authentication
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> {
                            // Your JWT configuration here.
                            // For example, if you have a custom JwtGrantedAuthoritiesConverter:
                            // jwt.jwtAuthenticationConverter(yourCustomJwtAuthenticationConverter);
                        })
                );
        return http.build();
    }
}
```java
// src/main/java/com/netflix/conductor/security/config/JwtTokenConverterConfig.java
package com.netflix.conductor.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Illustrative configuration for your JwtTokenConverter.
 * This ensures that your JWT claims (e.g., from 'realm_access.roles' or 'resource_access.account.roles')
 * are correctly mapped to Spring Security's GrantedAuthority objects.
 *
 * It's crucial that the roles extracted here match the 'actual role strings'
 * (e.g., "ADMIN", "WORKFLOW_MANAGER") that you define in your application.properties
 * via 'conductor.security.role.*' properties.
 */
@Configuration
public class JwtTokenConverterConfig {

    private final SecurityRulesConfig securityRulesConfig;

    public JwtTokenConverterConfig(SecurityRulesConfig securityRulesConfig) {
        this.securityRulesConfig = securityRulesConfig;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        // You can customize the authority prefix if needed, e.g., "ROLE_" (default)
        // grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = grantedAuthoritiesConverter.convert(jwt);
            authorities.addAll(extractCustomAuthorities(jwt));
            return authorities;
        });
        return jwtConverter;
    }

    /**
     * Extracts custom authorities from the JWT, potentially mapping them
     * based on your `securityRulesConfig.getRole()` map.
     * This is where you'd bridge your raw JWT roles to the conceptual roles
     * used in your security rules.
     *
     * @param jwt The JWT token.
     * @return A collection of GrantedAuthority objects.
     */
    private Collection<? extends GrantedAuthority> extractCustomAuthorities(Jwt jwt) {
        Set<String> extractedRoles = new HashSet<>();

        // Extract roles from 'realm_access.roles'
        Optional.ofNullable(jwt.getClaimAsMap("realm_access"))
                .map(realmAccess -> (List<String>) realmAccess.get("roles"))
                .orElse(List.of())
                .forEach(extractedRoles::add);

        // Extract roles from 'resource_access.account.roles' (or other resource access claims)
        Optional.ofNullable(jwt.getClaimAsMap("resource_access"))
                .map(resourceAccess -> (Map<String, List<String>>) resourceAccess.get("account"))
                .map(account -> account.get("roles"))
                .orElse(List.of())
                .forEach(extractedRoles::add);

        // Map extracted raw roles to your defined conceptual roles
        // This is where the `securityRulesConfig.getRole()` map comes into play
        return extractedRoles.stream()
                .map(rawRole -> {
                    // Check if there's a specific mapping for this raw role
                    // If not, use the raw role directly or apply a default prefix
                    String mappedRole = securityRulesConfig.getRole().entrySet().stream()
                            .filter(entry -> entry.getValue().equalsIgnoreCase(rawRole)) // Find if the raw role is a value in your map
                            .map(Map.Entry::getValue) // Get the mapped value (e.g., "ADMIN")
                            .findFirst()
                            .orElse(rawRole.toUpperCase()); // Fallback to uppercase raw role if no explicit mapping

                    // Ensure the role has the "ROLE_" prefix for Spring Security
                    return new SimpleGrantedAuthority("ROLE_" + mappedRole);
                })
                .collect(Collectors.toSet());
    }
}
```java
// src/main/java/com/netflix/conductor/rest/controller/WorkflowController.java
package com.netflix.conductor.rest.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Example Controller demonstrating the usage of @PreAuthorize annotation
 * with the custom permission evaluator.
 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    @GetMapping("/{workflowId}")
    @PreAuthorize("hasPermission('/api/workflow/**', 'READ')")
    public String getWorkflow(@PathVariable String workflowId) {
        return "Fetching workflow: " + workflowId;
    }

    @PostMapping
    @PreAuthorize("hasPermission('/api/workflow/**', 'CREATE')")
    public String createWorkflow(@RequestBody String workflowData) {
        return "Creating workflow with data: " + workflowData;
    }

    @PutMapping("/{workflowId}")
    @PreAuthorize("hasPermission('/api/workflow/**', 'UPDATE')")
    public String updateWorkflow(@PathVariable String workflowId, @RequestBody String workflowData) {
        return "Updating workflow: " + workflowId + " with data: " + workflowData;
    }

    @DeleteMapping("/{workflowId}")
    @PreAuthorize("hasPermission('/api/workflow/**', 'DELETE')")
    public String deleteWorkflow(@PathVariable String workflowId) {
        return "Deleting workflow: " + workflowId;
    }

    @GetMapping("/bulk")
    @PreAuthorize("hasPermission('/api/workflow/bulk/**', 'READ')")
    public String getBulkWorkflows() {
        return "Fetching bulk workflows.";
    }

    @PostMapping("/bulk")
    @PreAuthorize("hasPermission('/api/workflow/bulk/**', 'CREATE')")
    public String createBulkWorkflows(@RequestBody String bulkData) {
        return "Creating bulk workflows with data: " + bulkData;
    }

    @GetMapping("/admin/config")
    @PreAuthorize("hasPermission('/api/admin/**', 'READ')")
    public String getAdminConfig() {
        return "Accessing admin configuration.";
    }
}
```properties
# src/main/resources/application.properties
# --- Security Rules Configuration ---
conductor.security.rules[0].path=/api/admin/**
 conductor.security.rules[0].roles=admin
 conductor.security.rules[0].methods=GET,POST,PUT,DELETE

 conductor.security.rules[1].path=/api/queue/**
 conductor.security.rules[1].roles=admin,app_admin
 conductor.security.rules[1].methods=GET,POST,PUT,DELETE

 conductor.security.rules[2].path=/api/workflow/bulk/**
 conductor.security.rules[2].roles=workflow_manager,admin,app_workflow_manager,app_admin
 conductor.security.rules[2].methods=GET,POST,PUT,DELETE

 conductor.security.rules[3].path=/api/workflow/**
 conductor.security.rules[3].roles=read_only_user,workflow_manager,admin,unrestricted_worker,app_workflow_manager,app_admin
 conductor.security.rules[3].methods=GET

 conductor.security.rules[4].path=/api/workflow/**
 conductor.security.rules[4].roles=workflow_manager,admin,unrestricted_worker,app_workflow_manager,app_admin
 conductor.security.rules[4].methods=POST,PUT,DELETE

 # --- Role Mappings (Actual role strings that your JWT will provide) ---
 # These values should match the actual roles present in the JWT claims
 conductor.security.role.admin=ADMIN
 conductor.security.role.app_admin=APP_ADMIN
 conductor.security.role.read_only_user=READ_ONLY_USER
 conductor.security.role.workflow_manager=WORKFLOW_MANAGER
 conductor.security.role.unrestricted_worker=UNRESTRICTED_WORKER
 conductor.security.role.app_workflow_manager=APP_WORKFLOW_MANAGER

 # Spring Security JWT configuration (example, adjust as per your OIDC provider)
 spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8080/realms/microtx # Replace with your actual Keycloak/OIDC issuer URI






 // src/main/java/com/netflix/conductor/security/config/ConductorPermission.java
 package com.netflix.conductor.security.config;

 import java.util.Arrays;
 import java.util.List;
 import java.util.Set;
 import java.util.stream.Collectors;

 /**
 * Defines the high-level permissions for Conductor resources.
 * These map to typical CRUD operations and can be extended.
 */
public enum ConductorPermission {
    READ,
    CREATE,
    UPDATE,
    DELETE,
    EXECUTE; // Example for specific actions like executing a workflow

    /**
     * Converts a list of HTTP methods (e.g., "GET", "POST") into a set of ConductorPermissions.
     * This mapping is crucial for the PermissionEvaluator.
     *
     * @param httpMethods A list of HTTP method strings (e.g., "GET", "POST", "PUT", "DELETE").
     * @return A Set of corresponding ConductorPermission enums.
     */
    public static Set<ConductorPermission> fromHttpMethods(List<String> httpMethods) {
        if (httpMethods == null || httpMethods.isEmpty()) {
            return Set.of(); // No methods specified, no permissions
        }
        return httpMethods.stream()
                .map(String::toUpperCase)
                .flatMap(method -> {
                    switch (method) {
                        case "GET":
                            return Set.of(READ).stream();
                        case "POST":
                            return Set.of(CREATE).stream();
                        case "PUT":
                            return Set.of(UPDATE).stream();
                        case "DELETE":
                            return Set.of(DELETE).stream();
                        // Add more mappings if needed, e.g., for PATCH
                        default:
                            return Set.of().stream(); // Unrecognized method
                    }
                })
                .collect(Collectors.toSet());
    }
}
