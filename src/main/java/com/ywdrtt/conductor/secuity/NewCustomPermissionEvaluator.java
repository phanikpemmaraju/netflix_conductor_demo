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

@Component
public class NewCustomPermissionEvaluator implements PermissionEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomPermissionEvaluator.class);

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
        String currentUserId = authentication.getName(); // Get the current user's ID (e.g., subject from JWT)

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        LOGGER.debug("Evaluating object-level permission: targetType={}, permission={}, principal={}, authorities={}",
                targetType, perm, currentUserId, authorities);

        // --- Global Admin Override (for both User Admin and Application Admin) ---
        // Admin: Superuser. Full access to the system and resources. Can manage users and groups.
        if (authorities.contains(UserRole.ADMIN.withPrefix()) || authorities.contains(ApplicationRole.APP_ADMIN.withPrefix())) {
            LOGGER.debug("Admin (User or App) '{}' granted full access to '{}'.", currentUserId, targetType);
            return true;
        }

        // --- User Roles Specific Permissions ---

        // Regular user: Can only access resources that they created.
        if (authorities.contains(UserRole.USER.withPrefix())) {
            if ("MYRESOURCE".equals(targetType)) {
                MyResource resource = (MyResource) targetDomainObject;
                if (resource.getOwnerId() != null && resource.getOwnerId().equals(currentUserId)) {
                    LOGGER.debug("Regular User '{}' is owner of MyResource (ID: {}) and granted {}.", currentUserId, resource.getId(), perm);
                    return perm.equals(Permission.RESOURCE_READ.name()) ||
                            perm.equals(Permission.RESOURCE_UPDATE.name()) ||
                            perm.equals(Permission.RESOURCE_DELETE.name());
                }
            }
            // A regular user does not inherently get access to workflows or metadata unless they own it
            // (which would be covered by individual ownership checks if Workflow/Metadata had ownerId)
            // or if they also have another role (e.g., READ_ONLY_USER).
            // For now, no specific access to WORKFLOW/METADATA just based on USER role here.
        }

        // Read Only User: Can access all metadata and workflows in the system as read-only. Cannot modify or execute workflows.
        if (authorities.contains(UserRole.READ_ONLY_USER.withPrefix())) {
            if ("WORKFLOW".equals(targetType) && perm.equals(Permission.WORKFLOW_VIEW.name())) {
                LOGGER.debug("Read-Only User '{}' granted VIEW access to Workflow.", currentUserId);
                return true;
            }
            if ("METADATA".equals(targetType) && perm.equals(Permission.METADATA_READ.name())) {
                LOGGER.debug("Read-Only User '{}' granted READ access to Metadata.", currentUserId);
                return true;
            }
            // Cannot access secrets or tasks
            if ("SECRET".equals(targetType) && perm.equals(Permission.SECRET_READ.name())) {
                LOGGER.debug("Read-Only User '{}' denied access to SECRET.", currentUserId);
                return false; // Explicitly deny secrets to READ_ONLY_USER as per rule 8
            }
        }

        // Workflow Manager (User Role): Can view and execute all workflows in the system.
        // Can start, pause, resume, rerun, and delete any workflow execution in the cluster.
        if (authorities.contains(UserRole.WORKFLOW_MANAGER.withPrefix())) {
            if ("WORKFLOW".equals(targetType)) {
                LOGGER.debug("User Workflow Manager '{}' granted access to Workflow.", currentUserId);
                return perm.equals(Permission.WORKFLOW_VIEW.name()) ||
                        perm.equals(Permission.WORKFLOW_EXECUTE.name()) ||
                        perm.equals(Permission.WORKFLOW_MODIFY.name()) ||
                        perm.equals(Permission.WORKFLOW_DELETE.name());
            }
            // User Workflow Manager also has access to Secrets (Rule 8)
            if ("SECRET".equals(targetType) && perm.equals(Permission.SECRET_READ.name())) {
                LOGGER.debug("User Workflow Manager '{}' granted READ access to SECRET.", currentUserId);
                return true;
            }
        }

        // Metadata Manager (User Role): Can read, update, and delete all metadata in the system.
        // Can create, update, delete, and grant permissions to any workflow or task definition.
        if (authorities.contains(UserRole.METADATA_MANAGER.withPrefix())) {
            if ("METADATA".equals(targetType)) {
                LOGGER.debug("User Metadata Manager '{}' granted access to Metadata.", currentUserId);
                return perm.equals(Permission.METADATA_READ.name()) ||
                        perm.equals(Permission.METADATA_UPDATE.name()) ||
                        perm.equals(Permission.METADATA_DELETE.name()) ||
                        perm.equals(Permission.METADATA_CREATE.name()); // Assuming create for "manage"
            }
            // User Metadata Manager also has access to Secrets (Rule 8)
            if ("SECRET".equals(targetType) && perm.equals(Permission.SECRET_READ.name())) {
                LOGGER.debug("User Metadata Manager '{}' granted READ access to SECRET.", currentUserId);
                return true;
            }
        }

        // --- Application Roles Specific Permissions ---

        // Worker: Can poll and execute tasks that it has Execute permissions for.
        if (authorities.contains(ApplicationRole.WORKER.withPrefix())) {
            if ("TASK".equals(targetType) && perm.equals(Permission.WORKFLOW_POLL.name())) { // Assuming WORKFLOW_POLL for task polling
                LOGGER.debug("Worker app '{}' granted POLL access to TASK.", currentUserId);
                return true;
            }
            // If there's a specific TASK_EXECUTE permission and it's handled by this evaluator, add it here.
            // else if ("TASK".equals(targetType) && perm.equals(Permission.TASK_EXECUTE.name())) { return true; }
        }

        // Metadata API: Can create and manage workflow and task metadata.
        if (authorities.contains(ApplicationRole.METADATA_API.withPrefix())) {
            if ("METADATA".equals(targetType)) {
                LOGGER.debug("Metadata API app '{}' granted access to Metadata.", currentUserId);
                return perm.equals(Permission.METADATA_READ.name()) ||
                        perm.equals(Permission.METADATA_UPDATE.name()) ||
                        perm.equals(Permission.METADATA_DELETE.name()) ||
                        perm.equals(Permission.METADATA_CREATE.name());
            }
        }

        // Application API: Can create and manage applications.
        if (authorities.contains(ApplicationRole.APPLICATION_API.withPrefix())) {
            if ("APPLICATION".equals(targetType)) {
                LOGGER.debug("Application API app '{}' granted access to APPLICATION.", currentUserId);
                return perm.equals(Permission.APPLICATION_CREATE.name()) ||
                        perm.equals(Permission.APPLICATION_MANAGE.name()) ||
                        perm.equals(Permission.APPLICATION_READ.name());
            }
        }

        // Unrestricted Worker: Worker role with full access to poll and execute any task in the cluster.
        if (authorities.contains(ApplicationRole.UNRESTRICTED_WORKER.withPrefix())) {
            if ("WORKFLOW".equals(targetType)) {
                LOGGER.debug("Unrestricted Worker app '{}' granted VIEW/EXECUTE access to Workflow.", currentUserId);
                return perm.equals(Permission.WORKFLOW_VIEW.name()) ||
                        perm.equals(Permission.WORKFLOW_EXECUTE.name()) ||
                        perm.equals(Permission.WORKFLOW_MODIFY.name()) || // Full access implies modify
                        perm.equals(Permission.WORKFLOW_DELETE.name());   // Full access implies delete
            }
            if ("TASK".equals(targetType) && perm.equals(Permission.WORKFLOW_POLL.name())) { // or Permission.TASK_EXECUTE
                LOGGER.debug("Unrestricted Worker app '{}' granted POLL access to any TASK.", currentUserId);
                return true;
            }
        }

        // APP_METADATA_MANAGER (Application Role): Can create, update, delete, and grant permissions to any workflow or task definition.
        if (authorities.contains(ApplicationRole.APP_METADATA_MANAGER.withPrefix())) {
            if ("METADATA".equals(targetType)) {
                LOGGER.debug("App Metadata Manager app '{}' granted access to Metadata.", currentUserId);
                return perm.equals(Permission.METADATA_READ.name()) ||
                        perm.equals(Permission.METADATA_UPDATE.name()) ||
                        perm.equals(Permission.METADATA_DELETE.name()) ||
                        perm.equals(Permission.METADATA_CREATE.name());
            }
            // Also has access to Secrets (Rule 8)
            if ("SECRET".equals(targetType) && perm.equals(Permission.SECRET_READ.name())) {
                LOGGER.debug("App Metadata Manager app '{}' granted READ access to SECRET.", currentUserId);
                return true;
            }
        }

        // APP_WORKFLOW_MANAGER (Application Role): Can start, pause, resume, rerun, and delete any workflow execution.
        if (authorities.contains(ApplicationRole.APP_WORKFLOW_MANAGER.withPrefix())) {
            if ("WORKFLOW".equals(targetType)) {
                LOGGER.debug("App Workflow Manager app '{}' granted access to Workflow.", currentUserId);
                return perm.equals(Permission.WORKFLOW_VIEW.name()) ||
                        perm.equals(Permission.WORKFLOW_EXECUTE.name()) ||
                        perm.equals(Permission.WORKFLOW_MODIFY.name()) ||
                        perm.equals(Permission.WORKFLOW_DELETE.name());
            }
            if ("TASK".equals(targetType) && perm.equals(Permission.WORKFLOW_POLL.name())) { // Or TASK_EXECUTE
                LOGGER.debug("App Workflow Manager app '{}' granted POLL access to TASK.", currentUserId);
                return true;
            }
            // Also has access to Secrets (Rule 8)
            if ("SECRET".equals(targetType) && perm.equals(Permission.SECRET_READ.name())) {
                LOGGER.debug("App Workflow Manager app '{}' granted READ access to SECRET.", currentUserId);
                return true;
            }
        }

        // APP_APPLICATION_MANAGER (Application Role): Can create, update, and delete any application.
        if (authorities.contains(ApplicationRole.APP_APPLICATION_MANAGER.withPrefix())) {
            if ("APPLICATION".equals(targetType)) {
                LOGGER.debug("App Application Manager app '{}' granted access to APPLICATION.", currentUserId);
                return perm.equals(Permission.APPLICATION_CREATE.name()) ||
                        perm.equals(Permission.APPLICATION_MANAGE.name()) || // Covers update/delete
                        perm.equals(Permission.APPLICATION_READ.name()); // Implicitly read for manage
            }
        }

        // Default deny for all other cases
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

        // --- Global Admin Override (for both User Admin and Application Admin) ---
        if (authorities.contains(UserRole.ADMIN.withPrefix()) || authorities.contains(ApplicationRole.APP_ADMIN.withPrefix())) {
            LOGGER.debug("Admin (User or App) '{}' granted ID-based access to '{}' (ID: {}).", currentUserId, type, targetId);
            return true;
        }

        // --- Fetch resource by ID and delegate to object-level permission check ---
        switch (type) {
            case "MYRESOURCE":
                MyResource resource = myResourceService.findById((Long) targetId);
                if (resource == null) {
                    LOGGER.warn("MyResource with ID {} not found for permission check. Denying access.", targetId);
                    return false;
                }
                return hasPermission(authentication, resource, permission);
            case "WORKFLOW":
                Workflow workflow = workflowService.findById((Long) targetId);
                if (workflow == null) {
                    LOGGER.warn("Workflow with ID {} not found for permission check. Denying access.", targetId);
                    return false;
                }
                return hasPermission(authentication, workflow, permission);
            case "METADATA":
                Metadata metadata = metadataService.findById((Long) targetId);
                if (metadata == null) {
                    LOGGER.warn("Metadata with ID {} not found for permission check. Denying access.", targetId);
                    return false;
                }
                return hasPermission(authentication, metadata, permission);
            case "SECRET":
                // Secrets permission is typically based on roles, not individual secret ownership.
                // Rule 8: Secrets - Access for WORKFLOW_MANAGER, METADATA_MANAGER, ADMIN (EXCLUDES READ_ONLY_USER)
                // Now also includes App Workflow Manager, App Metadata Manager, App Admin
                return !authorities.contains(UserRole.READ_ONLY_USER.withPrefix()); // READ_ONLY_USER is explicitly excluded
            // Other roles are covered by the URL-level rules or specific object-level checks if secrets
            // had owners. For now, this is simpler based on the description.
            case "APPLICATION":
                // Application permission is based on roles (APPLICATION_API, APP_APPLICATION_MANAGER, ADMIN, APP_ADMIN)
                return authorities.contains(ApplicationRole.APPLICATION_API.withPrefix()) ||
                        authorities.contains(ApplicationRole.APP_APPLICATION_MANAGER.withPrefix()); // ADMIN handled by global rule
            case "TASK":
                // Task permission is based on roles (WORKER, UNRESTRICTED_WORKER, APP_WORKFLOW_MANAGER, ADMIN, APP_ADMIN)
                return authorities.contains(ApplicationRole.WORKER.withPrefix()) ||
                        authorities.contains(ApplicationRole.UNRESTRICTED_WORKER.withPrefix()) ||
                        authorities.contains(ApplicationRole.APP_WORKFLOW_MANAGER.withPrefix()); // ADMIN handled by global rule
            default:
                LOGGER.warn("Unknown target type '{}' for ID-based permission check. Denying access.", type);
                return false;
        }
    }
}
