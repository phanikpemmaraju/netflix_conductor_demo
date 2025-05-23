package com.ywdrtt.conductor.working;

package com.yourcompany.yourconductorapp.security;

import com.yourcompany.yourconductorapp.config.ConductorRoleConfig;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

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
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(role -> role.startsWith("ROLE_"))
                .map(role -> role.substring(5).toUpperCase())
                .collect(Collectors.toList());
    }

    private boolean hasAnyRequiredRole(List<String> userExtractedRoles, List<String> configuredRoles) {
        if (userExtractedRoles.isEmpty() || configuredRoles.isEmpty()) {
            return false;
        }
        return configuredRoles.stream()
                .map(String::toUpperCase)
                .anyMatch(userExtractedRoles::contains);
    }

    // --- hasPermission overload for global actions (e.g., hasPermission('workflow', ConductorPermission.CREATE)) ---
    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (!(authentication.getPrincipal() instanceof Jwt)) {
            return false;
        }
        if (!(permission instanceof ConductorPermission)) {
            // This case should ideally not happen if calling from well-formed @PreAuthorize expressions
            System.err.println("Error: Permission object is not of type ConductorPermission: " + (permission != null ? permission.getClass().getName() : "null"));
            return false;
        }

        List<String> userRoles = extractUserRoles(authentication);
        String targetType = String.valueOf(targetDomainObject).toLowerCase();
        ConductorPermission action = (ConductorPermission) permission;

        // 1. Admin always has full access
        if (hasAnyRequiredRole(userRoles, conConfig.adminRoles())) {
            return true;
        }

        // 2. Workflow Manager (for workflow-related actions)
        if (targetType.startsWith("workflow")) {
            if (hasAnyRequiredRole(userRoles, conConfig.workflowManagerRoles())) {
                return true;
            }
        }

        // 3. Metadata Manager (for metadata-related actions)
        if (targetType.equals("metadata")) {
            if (hasAnyRequiredRole(userRoles, conConfig.metadataManagerRoles())) {
                return true;
            }
        }

        // 4. Basic User access for creation (assuming creation doesn't require ownership initially)
        if (hasAnyRequiredRole(userRoles, conConfig.userRoles())) {
            if (action == ConductorPermission.CREATE) {
                switch (targetType) {
                    case "workflow":
                    case "metadata":
                    case "event":
                        return true;
                }
            }
        }

        return false;
    }

    // --- hasPermission overload for resource-specific actions (e.g., hasPermission(workflowId, 'workflow', ConductorPermission.READ)) ---
    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        if (!(permission instanceof ConductorPermission)) {
            // This case should ideally not happen if calling from well-formed @PreAuthorize expressions
            System.err.println("Error: Permission object is not of type ConductorPermission: " + (permission != null ? permission.getClass().getName() : "null"));
            return false;
        }

        List<String> userRoles = extractUserRoles(authentication);
        ConductorPermission action = (ConductorPermission) permission;
        String authenticatedUserId = jwt.getSubject();

        // 1. Admin always has full access
        if (hasAnyRequiredRole(userRoles, conConfig.adminRoles())) {
            return true;
        }

        // 2. Workflow Manager (for workflow-related actions)
        if (targetType.equalsIgnoreCase("workflow") || targetType.equalsIgnoreCase("workflow-bulk")) {
            if (hasAnyRequiredRole(userRoles, conConfig.workflowManagerRoles())) {
                return true;
            }
        }

        // 3. Metadata Manager (for metadata-related actions)
        if (targetType.equalsIgnoreCase("metadata")) {
            if (hasAnyRequiredRole(userRoles, conConfig.metadataManagerRoles())) {
                return true;
            }
        }

        // 4. "User" (basic access and ownership)
        if (hasAnyRequiredRole(userRoles, conConfig.userRoles())) {
            switch (targetType.toLowerCase()) {
                case "workflow":
                case "metadata":
                case "event":
                case "task":
                    boolean isOwner = checkResourceOwnership(authenticatedUserId, targetId, targetType);
                    if (isOwner) {
                        return action == ConductorPermission.READ ||
                                action == ConductorPermission.UPDATE ||
                                action == ConductorPermission.DELETE;
                    }
                    return false;
                case "health-check": // Health check can be accessed by any user
                    return action == ConductorPermission.READ;
                default:
                    return false;
            }
        }

        return false;
    }

    /**
     * Placeholder for actual ownership check logic.
     * This method needs to be implemented based on your application's data model and services.
     *
     * @param userId The ID of the currently authenticated user.
     * @param resourceId The ID of the resource being accessed.
     * @param resourceType The type of the resource (e.g., "workflow", "metadata").
     * @return true if the user owns the resource, false otherwise.
     */
    private boolean checkResourceOwnership(String userId, Serializable resourceId, String resourceType) {
        // --- !!! IMPORTANT: REPLACE THIS WITH YOUR REAL OWNERSHIP LOGIC !!! ---
        System.out.println("DEBUG: Performing ownership check for resourceType=" + resourceType + ", resourceId=" + resourceId + ", userId=" + userId);
        return resourceId != null && resourceId.toString().contains(userId); // Dummy check
    }
}