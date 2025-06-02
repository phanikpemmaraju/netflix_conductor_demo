package com.ywdrtt.conductor.secuity;

package com.yourcompany.app.security;

import com.yourcompany.app.enums.ApplicationRole;
import com.yourcompany.app.enums.Permission;
import com.yourcompany.app.enums.UserRole;
import com.yourcompany.app.model.Metadata;
import com.yourcompany.app.model.MyResource;
import com.yourcompany.app.model.Workflow;
import com.yourcompany.app.service.MetadataService;
import com.yourcompany.app.service.MyResourceService;
import com.yourcompany.app.service.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays; // Unused, can be removed

@Component
public class CustomPermissionEvaluator1 implements PermissionEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomPermissionEvaluator1.class);

    private final MyResourceService myResourceService;
    private final WorkflowService workflowService;
    private final MetadataService metadataService;

    public CustomPermissionEvaluator(MyResourceService myResourceService, WorkflowService workflowService, MetadataService metadataService) {
        this.myResourceService = myResourceService;
        this.workflowService = workflowService;
        this.metadataService = metadataService;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if ((authentication == null) || (targetDomainObject == null) || !(permission instanceof String)) {
            LOGGER.debug("Permission check failed: Invalid authentication, target object, or permission type.");
            return false;
        }

        String targetType = targetDomainObject.getClass().getSimpleName().toUpperCase();
        String perm = (String) permission;
        String currentUserId = authentication.getName(); // Get the current user's ID

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        LOGGER.debug("Evaluating object-level permission: targetType={}, permission={}, principal={}, authorities={}",
                targetType, perm, currentUserId, authorities);

        // --- Global Admin Override ---
        if (authorities.contains(UserRole.ADMIN.withPrefix())) {
            LOGGER.debug("Admin user '{}' granted access to '{}'.", currentUserId, targetType);
            return true;
        }

        // --- Resource-Specific Ownership/Metadata Checks ---

        if ("MYRESOURCE".equals(targetType)) {
            MyResource resource = (MyResource) targetDomainObject;
            // Check if the current user is the owner of the resource
            if (resource.getOwnerId() != null && resource.getOwnerId().equals(currentUserId)) {
                LOGGER.debug("User '{}' is owner of MyResource (ID: {}) and granted {}.", currentUserId, resource.getId(), perm);
                // Owners can typically read, update, and delete their own resources
                return perm.equals(Permission.RESOURCE_READ.name()) ||
                        perm.equals(Permission.RESOURCE_UPDATE.name()) ||
                        perm.equals(Permission.RESOURCE_DELETE.name());
            }
            // Fall through to role-based checks if not owner, or if ownership doesn't grant the specific permission
        }

        if ("WORKFLOW".equals(targetType)) {
            Workflow workflow = (Workflow) targetDomainObject;
            // Check if the current user is associated with this workflow (e.g., owner, creator, or specifically allowed user)
            // Assuming Workflow class has a method like getOwnerId() or getAuthorizedUsers()
            // For this example, I'll assume getOwnerId() exists or similar.
            if (workflow.getOwnerId() != null && workflow.getOwnerId().equals(currentUserId)) {
                LOGGER.debug("User '{}' is owner of Workflow (ID: {}) and granted {}.", currentUserId, workflow.getId(), perm);
                // Owners typically have full control or specific permissions on their workflows
                return perm.equals(Permission.WORKFLOW_VIEW.name()) ||
                        perm.equals(Permission.WORKFLOW_EXECUTE.name()) ||
                        perm.equals(Permission.WORKFLOW_MODIFY.name()) || // Added a hypothetical modify permission for owners
                        perm.equals(Permission.WORKFLOW_DELETE.name());    // Added a hypothetical delete permission for owners
            }
            // Role-based checks for workflows
            if (authorities.contains(UserRole.WORKFLOW_MANAGER.withPrefix()) ||
                    authorities.contains(ApplicationRole.UNRESTRICTED_WORKER.withPrefix())) {
                LOGGER.debug("User '{}' has role-based access for Workflow.", currentUserId);
                return perm.equals(Permission.WORKFLOW_VIEW.name()) || perm.equals(Permission.WORKFLOW_EXECUTE.name());
            }
            if (authorities.contains(UserRole.READ_ONLY_USER.withPrefix()) && perm.equals(Permission.WORKFLOW_VIEW.name())) {
                LOGGER.debug("User '{}' has read-only access for Workflow.", currentUserId);
                return true;
            }
        }

        if ("METADATA".equals(targetType)) {
            Metadata metadata = (Metadata) targetDomainObject;
            // Check if the current user is associated with this metadata (e.g., creator, last modifier, or specifically allowed user)
            // Assuming Metadata class has a method like getCreatedBy() or getOwnerId().
            // For this example, I'll assume getCreatedBy() exists or similar.
            if (metadata.getCreatedBy() != null && metadata.getCreatedBy().equals(currentUserId)) {
                LOGGER.debug("User '{}' is creator of Metadata (ID: {}) and granted {}.", currentUserId, metadata.getId(), perm);
                // Creators typically have full control or specific permissions on their metadata
                return perm.equals(Permission.METADATA_READ.name()) ||
                        perm.equals(Permission.METADATA_UPDATE.name()) ||
                        perm.equals(Permission.METADATA_DELETE.name());
            }
            // Role-based checks for metadata
            if (authorities.contains(UserRole.METADATA_MANAGER.withPrefix()) ||
                    authorities.contains(ApplicationRole.METADATA_API.withPrefix())) {
                LOGGER.debug("User '{}' has role-based access for Metadata.", currentUserId);
                return perm.equals(Permission.METADATA_READ.name()) ||
                        perm.equals(Permission.METADATA_UPDATE.name()) ||
                        perm.equals(Permission.METADATA_DELETE.name());
            }
            if (authorities.contains(UserRole.READ_ONLY_USER.withPrefix()) && perm.equals(Permission.METADATA_READ.name())) {
                LOGGER.debug("User '{}' has read-only access for Metadata.", currentUserId);
                return true;
            }
        }

        // --- Other Specific Type Checks (no direct userId ownership assumed here) ---
        if ("SECRET".equals(targetType) && perm.equals(Permission.SECRET_READ.name())) {
            return !authorities.contains(UserRole.READ_ONLY_USER.withPrefix());
        }

        if ("TASK".equals(targetType) && perm.equals(Permission.WORKFLOW_POLL.name()) && authorities.contains(ApplicationRole.WORKER.withPrefix())) {
            return true;
        }
        if ("APPLICATION".equals(targetType) && authorities.contains(ApplicationRole.APPLICATION_API.withPrefix())) {
            if (perm.equals(Permission.APPLICATION_CREATE.name()) || perm.equals(Permission.APPLICATION_MANAGE.name())) {
                return true;
            }
        }

        LOGGER.debug("Permission denied for user '{}' on object type '{}' with permission '{}'. No matching rules found.",
                currentUserId, targetType, perm);
        return false;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if ((authentication == null) || (targetId == null) || (targetType == null) || !(permission instanceof String)) {
            LOGGER.debug("Permission check (ID-based) failed: Invalid authentication, target ID, target type, or permission type.");
            return false;
        }

        String perm = (String) permission;
        String type = targetType.toUpperCase();
        String currentUserId = authentication.getName(); // Get the current user's ID

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        LOGGER.debug("Evaluating ID-based permission: targetId={}, targetType={}, permission={}, principal={}, authorities={}",
                targetId, type, perm, currentUserId, authorities);

        // --- Global Admin Override ---
        if (authorities.contains(UserRole.ADMIN.withPrefix())) {
            LOGGER.debug("Admin user '{}' granted ID-based access to '{}' (ID: {}).", currentUserId, type, targetId);
            return true;
        }

        // --- Fetch resource by ID and delegate to object-level permission check ---
        switch (type) {
            case "MYRESOURCE":
                MyResource resource = myResourceService.findById((Long) targetId);
                if (resource == null) {
                    LOGGER.warn("MyResource with ID {} not found for permission check.", targetId);
                    return false; // Resource not found, deny access
                }
                return hasPermission(authentication, resource, permission);
            case "WORKFLOW":
                Workflow workflow = workflowService.findById((Long) targetId);
                if (workflow == null) {
                    LOGGER.warn("Workflow with ID {} not found for permission check.", targetId);
                    return false; // Workflow not found, deny access
                }
                return hasPermission(authentication, workflow, permission);
            case "METADATA":
                Metadata metadata = metadataService.findById((Long) targetId);
                if (metadata == null) {
                    LOGGER.warn("Metadata with ID {} not found for permission check.", targetId);
                    return false; // Metadata not found, deny access
                }
                return hasPermission(authentication, metadata, permission);
            case "SECRET":
                return !authorities.contains(UserRole.READ_ONLY_USER.withPrefix());
            case "APPLICATION":
                return authorities.contains(ApplicationRole.APPLICATION_API.withPrefix());
            case "TASK":
                return authorities.contains(ApplicationRole.WORKER.withPrefix());
            default:
                LOGGER.warn("Unknown target type '{}' for ID-based permission check.", type);
                return false;
        }
    }
}