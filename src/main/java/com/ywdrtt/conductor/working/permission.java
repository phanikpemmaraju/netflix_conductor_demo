package com.yourcompany.yourconductorapp.security;

import com.yourcompany.yourconductorapp.config.ConductorRoleConfig;
import org.springframework.cache.annotation.Cacheable; // Make sure to import
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays; // For Arrays.asList

@Component("conductorPermissionEvaluator")
public class ConductorPermissionEvaluator implements PermissionEvaluator {

    private final ConductorRoleConfig conConfig;

    public ConductorPermissionEvaluator(ConductorRoleConfig conConfig) {
        this.conConfig = conConfig;
    }

    private List<String> extractUserRoles(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return Collections.emptyList();
        }
        var authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(role -> role.startsWith("ROLE_"))
                .map(role -> role.substring(5).toUpperCase())
                .collect(Collectors.toList());
        // System.out.println("DEBUG: User roles extracted: " + authorities); // Keep for debugging if needed
        return authorities;
    }

    private boolean hasAnyRequiredRole(List<String> userExtractedRoles, List<String> configuredRoles) {
        if (userExtractedRoles.isEmpty() || configuredRoles.isEmpty()) {
            return false;
        }
        var result = configuredRoles.stream()
                .map(String::toUpperCase)
                .anyMatch(userExtractedRoles::contains);
        // System.out.println("DEBUG: Checking roles: User has " + userExtractedRoles + ", required " + configuredRoles + ". Result: " + result); // Keep for debugging if needed
        return result;
    }

    /**
     * Calculates and caches a map of effective permissions for a given user.
     * The map structure: Map<ResourceType (String), Set<ConductorPermission>>
     */
    @Cacheable(value = "userPermissions", key = "#authentication.name")
    public Map<String, Set<ConductorPermission>> getEffectiveUserPermissions(Authentication authentication) {
        Map<String, Set<ConductorPermission>> userPermissions = new HashMap<>();
        List<String> userRoles = extractUserRoles(authentication);

        // Define all possible resource types and actions from your matrix
        List<String> allResourceTypes = Arrays.asList(
            "workflow", "workflow-bulk", "metadata", "event", "task", "health-check", "admin", "queue-admin"
        );
        List<ConductorPermission> allPermissions = Arrays.asList(
            ConductorPermission.READ, ConductorPermission.CREATE, ConductorPermission.UPDATE, ConductorPermission.DELETE, ConductorPermission.EXECUTE
        );

        // --- Role-based Permission Calculation ---

        // ADMIN has full access to everything
        if (hasAnyRequiredRole(userRoles, conConfig.admin())) {
            for (String resourceType : allResourceTypes) {
                userPermissions.computeIfAbsent(resourceType, k -> new HashSet<>()).addAll(allPermissions);
            }
            return userPermissions; // Admins are done, no need to check other roles
        }

        // Workflow Manager permissions (workflow-resource, workflow-bulk-resource)
        if (hasAnyRequiredRole(userRoles, conConfig.workflowManager())) {
            userPermissions.computeIfAbsent("workflow", k -> new HashSet<>()).addAll(allPermissions); // Assume all actions for workflow
            userPermissions.computeIfAbsent("workflow-bulk", k -> new HashSet<>()).addAll(allPermissions); // Assume all actions for workflow-bulk
        }

        // Metadata Manager permissions (metadata-resource)
        if (hasAnyRequiredRole(userRoles, conConfig.metadataManager())) {
            userPermissions.computeIfAbsent("metadata", k -> new HashSet<>()).addAll(allPermissions); // Assume all actions for metadata
        }

        // Basic User permissions (layered on top, or defined if not already covered)
        if (hasAnyRequiredRole(userRoles, conConfig.user())) {
            // Workflow: user* has GET, POST, PUT, DELETE, EXECUTE (assuming EXECUTE for start/pause/resume)
            userPermissions.computeIfAbsent("workflow", k -> new HashSet<>()).addAll(Arrays.asList(
                ConductorPermission.READ, ConductorPermission.CREATE, ConductorPermission.UPDATE, ConductorPermission.DELETE, ConductorPermission.EXECUTE));

            // Workflow-Bulk: user* has GET
            userPermissions.computeIfAbsent("workflow-bulk", k -> new HashSet<>()).add(ConductorPermission.READ);

            // Metadata: user* has GET, POST, PUT, DELETE
            userPermissions.computeIfAbsent("metadata", k -> new HashSet<>()).addAll(Arrays.asList(
                ConductorPermission.READ, ConductorPermission.CREATE, ConductorPermission.UPDATE, ConductorPermission.DELETE));

            // Event: user* has GET, POST, PUT
            userPermissions.computeIfAbsent("event", k -> new HashSet<>()).addAll(Arrays.asList(
                ConductorPermission.READ, ConductorPermission.CREATE, ConductorPermission.UPDATE));

            // Task: user* has GET, POST
            userPermissions.computeIfAbsent("task", k -> new HashSet<>()).addAll(Arrays.asList(
                ConductorPermission.READ, ConductorPermission.CREATE));

            // Health-check: user* has GET
            userPermissions.computeIfAbsent("health-check", k -> new HashSet<>()).add(ConductorPermission.READ);
        }

        // Add any other roles (e.g., Queue Admin if applicable and not covered by Admin)
        // If Queue Admin exists and is separate from general Admin
        // if (hasAnyRequiredRole(userRoles, conConfig.queueAdmin())) { // Assuming you add queueAdmin role to ConductorRoleConfig
        //     userPermissions.computeIfAbsent("queue-admin", k -> new HashSet<>()).addAll(allPermissions); // Or specific queue admin permissions
        // }

        return userPermissions;
    }


    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        var targetType = String.valueOf(targetDomainObject).toLowerCase();
        return hasPermission(authentication, null, targetType, permission);
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if (!(authentication.getPrincipal() instanceof Jwt)) {
            return false;
        }
        if (!(permission instanceof ConductorPermission action)) {
            System.err.println("Error: Permission object is not of type ConductorPermission: " + (permission != null ? permission.getClass().getName() : "null"));
            return false;
        }

        // Get the cached permissions map for the user
        Map<String, Set<ConductorPermission>> userEffectivePermissions = getEffectiveUserPermissions(authentication);

        // Perform a quick in-memory lookup
        Set<ConductorPermission> allowedActions = userEffectivePermissions.getOrDefault(targetType.toLowerCase(), Collections.emptySet());

        boolean granted = allowedActions.contains(action);

        // Debugging (optional, remove in production)
        System.out.println("DEBUG: Permission Check - User: " + authentication.getName() +
                           ", TargetType: " + targetType + ", Action: " + action +
                           ", Allowed: " + allowedActions + ", Result: " + granted);

        return granted;
    }
}
