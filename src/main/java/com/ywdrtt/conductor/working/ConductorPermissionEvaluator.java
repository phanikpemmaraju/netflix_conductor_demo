package com.ywdrtt.conductor.working;

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
        var authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(role -> role.startsWith("ROLE_"))
                .map(role -> role.substring(5).toUpperCase())
                .collect(Collectors.toList());
        System.out.println("DEBUG: User roles extracted: " + authorities); // Added for debugging
        return authorities;
    }

    private boolean hasAnyRequiredRole(List<String> userExtractedRoles, List<String> configuredRoles) {
        if (userExtractedRoles.isEmpty() || configuredRoles.isEmpty()) {
            return false;
        }
        var result = configuredRoles.stream()
                .map(String::toUpperCase)
                .anyMatch(userExtractedRoles::contains);
        System.out.println("DEBUG: Checking roles: User has " + userExtractedRoles + ", required " + configuredRoles + ". Result: " + result); // Added for debugging
        return result;
    }

    // --- hasPermission overload for global actions (e.g., hasPermission('workflow', ConductorPermission.CREATE)) ---
    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (!(authentication.getPrincipal() instanceof Jwt)) {
            return false;
        }
        if (!(permission instanceof ConductorPermission action)) {
            System.err.println("Error: Permission object is not of type ConductorPermission: " + (permission != null ? permission.getClass().getName() : "null"));
            return false;
        }

        var userRoles = extractUserRoles(authentication);
        var targetType = String.valueOf(targetDomainObject).toLowerCase();

        System.out.println("DEBUG: Global Permission Check - TargetType: " + targetType + ", Action: " + action + ", UserRoles: " + userRoles);

        // 1. Admin always has full access (Superuser)
        if (hasAnyRequiredRole(userRoles, conConfig.adminRoles())) {
            return true;
        }

        // 2. Workflow Manager (for workflow-related actions, any permission)
        if (targetType.startsWith("workflow") || targetType.equals("workflow-bulk")) {
            if (hasAnyRequiredRole(userRoles, conConfig.workflowManagerRoles())) {
                return true;
            }
        }

        // 3. Metadata Manager (for metadata-related actions, any permission)
        if (targetType.equals("metadata")) {
            if (hasAnyRequiredRole(userRoles, conConfig.metadataManagerRoles())) {
                return true;
            }
        }

        // 4. Basic User access for creation (assuming "user" role can create some resources)
        // This is based on the screenshot showing "user*" for POST on workflow, metadata, event.
        if (hasAnyRequiredRole(userRoles, conConfig.userRoles())) {
            if (action == ConductorPermission.CREATE || action == ConductorPermission.READ) { // Users can POST (CREATE) and GET (READ)
                return switch (targetType) {
                    case "workflow", "metadata", "event", "task" -> true; // Task is GET only, covered by READ
                    case "health-check" -> true; // Health check is GET, covered by READ
                    default -> false;
                };
            }
        }

        return false; // Deny by default
    }

    // --- hasPermission overload for resource-specific actions (e.g., hasPermission(workflowId, 'workflow', ConductorPermission.READ)) ---
    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if (!(authentication.getPrincipal() instanceof Jwt)) { // No variable needed for Jwt here
            return false;
        }
        if (!(permission instanceof ConductorPermission action)) { // Extract 'action' directly
            System.err.println("Error: Permission object is not of type ConductorPermission: " + (permission != null ? permission.getClass().getName() : "null"));
            return false;
        }

        var userRoles = extractUserRoles(authentication);
        // The 'authenticatedUserId' and 'resourceId' are NOT used if ownership is removed.

        System.out.println("DEBUG: Resource-Specific Permission Check - TargetType: " + targetType + ", TargetId: " + targetId + ", Action: " + action + ", UserRoles: " + userRoles);


        // 1. Admin always has full access (Superuser)
        if (hasAnyRequiredRole(userRoles, conConfig.adminRoles())) {
            return true;
        }

        // 2. Workflow Manager (for workflow-related actions)
        // Matches "workflow manager" for workflow-resource (GET, POST, PUT, DELETE) & workflow-bulk-resource (GET, POST, PUT, DELETE)
        if (targetType.equalsIgnoreCase("workflow") || targetType.equalsIgnoreCase("workflow-bulk")) {
            if (hasAnyRequiredRole(userRoles, conConfig.workflowManagerRoles())) {
                return true;
            }
        }

        // 3. Metadata Manager (for metadata-related actions)
        // Matches "Metadata manager" for metadata-resource (GET, POST, PUT, DELETE)
        if (targetType.equalsIgnoreCase("metadata")) {
            if (hasAnyRequiredRole(userRoles, conConfig.metadataManagerRoles())) {
                return true;
            }
        }

        // 4. Basic User access (Based on "user*" in screenshot)
        if (hasAnyRequiredRole(userRoles, conConfig.userRoles())) {
            // These cases imply the "user" role is sufficient, without ownership
            return switch (targetType.toLowerCase()) {
                case "workflow": // user* is allowed for GET, POST, PUT, DELETE
                case "event":    // user* is allowed for GET, POST, PUT
                case "task":     // user* is allowed for GET
                case "health-check": // user* is allowed for GET
                case "metadata": // user* is allowed for GET, POST, PUT, DELETE
                    // For these types, any of the specified actions are allowed if the user has the 'USER' role
                    yield switch (action) {
                    case READ, CREATE, UPDATE, DELETE, EXECUTE -> true; // Be specific about allowed actions
                    default -> false;
                };
                    default -> false;
            };
        }

        // 5. Admin and Queue-Admin specific roles (if not covered by general admin above)
        // For /queue-admin-resource and /admin-resource, only 'admin' is listed.
        // This is primarily handled by the 'adminRoles' check at the top.
        // If you had specific roles for these that were NOT 'ADMIN', they'd go here.

        return false; // Deny by default
    }

    // Removed the checkResourceOwnership method as it's no longer needed under this interpretation.
    // If you ever need ownership for *other* specific resources, re-introduce it.
}