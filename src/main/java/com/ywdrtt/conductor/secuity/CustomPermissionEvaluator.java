package com.yourcompany.app.security; // Corrected package declaration

// Make sure these imports match your actual project structure
import com.yourcompany.app.enums.ApplicationRole;
import com.yourcompany.app.enums.Permission; // This import refers to your Permission enum file
import com.yourcompany.app.enums.UserRole;
import com.yourcompany.app.model.Metadata;
import com.yourcompany.app.model.MyResource; // Assuming MyResource model exists
import com.yourcompany.app.model.Workflow;
import com.yourcompany.app.service.MetadataService;
import com.yourcompany.app.service.MyResourceService; // Assuming MyResourceService exists
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

/**
 * IMPORTANT: The Permission enum used in this class should be defined in a separate file:
 * src/main/java/com/yourcompany/app/enums/Permission.java
 *
 * It should contain all the following entries:
 *
 * package com.yourcompany.app.enums;
 *
 * public enum Permission {
 * // --- Resource Ownership Permissions (for MyResource, and conceptually for Workflow/Metadata owners) ---
 * RESOURCE_CREATE,
 * RESOURCE_READ,
 * RESOURCE_UPDATE,
 * RESOURCE_DELETE,
 *
 * // --- Workflow Permissions ---
 * WORKFLOW_VIEW,       // To view/get workflows (used by READ_ONLY_USER, etc.)
 * WORKFLOW_EXECUTE,    // To start, rerun, or execute workflows (used by WORKFLOW_MANAGER, UNRESTRICTED_WORKER, etc.)
 * WORKFLOW_MODIFY,     // To pause, resume, skip tasks, or other modifications (used by owners, WORKFLOW_MANAGER, APP_WORKFLOW_MANAGER)
 * WORKFLOW_DELETE,     // To terminate or delete workflows (used by owners, WORKFLOW_MANAGER, APP_WORKFLOW_MANAGER)
 * WORKFLOW_POLL,       // For workers to poll for tasks within a workflow context
 * WORKFLOW_CREATE,     // To define/create new workflows (e.g., via /workflow endpoint POST)
 * WORKFLOW_BULK_ACTION, // For bulk operations like pause/resume/terminate multiple workflows
 *
 * // --- Metadata Permissions (for TaskDefs, WorkflowDefs, EventHandlers) ---
 * METADATA_READ,       // To get definitions (used by READ_ONLY_USER, METADATA_MANAGER, METADATA_API)
 * METADATA_CREATE,     // To register/create new definitions (used by METADATA_MANAGER, METADATA_API)
 * METADATA_UPDATE,     // To update existing definitions (used by METADATA_MANAGER, METADATA_API)
 * METADATA_DELETE,     // To unregister/delete definitions (used by METADATA_MANAGER, METADATA_API)
 *
 * // --- Application Management Permissions ---
 * APPLICATION_CREATE,  // To create new applications
 * APPLICATION_MANAGE,  // To manage (update/delete) applications
 * APPLICATION_READ,    // To read application details (implicitly needed for 'manage')
 *
 * // --- User & Group Management Permissions (if these are handled by your API directly) ---
 * USER_MANAGE,
 * GROUP_MANAGE,
 *
 * // --- Admin-Specific Permissions (for /admin endpoints) ---
 * ADMIN_READ,
 * ADMIN_WRITE,
 *
 * // --- Event Handler Permissions ---
 * EVENT_CREATE,
 * EVENT_READ,
 * EVENT_UPDATE,
 * EVENT_DELETE,
 *
 * // --- Queue Management Permissions ---
 * QUEUE_READ,
 * QUEUE_WRITE,
 * QUEUE_DELETE,
 * QUEUE_PURGE,
 *
 * // --- Task Specific Permissions ---
 * TASK_READ,           // To get task details, poll data
 * TASK_UPDATE,         // To update task status/results
 * TASK_LOG,            // To view/add task execution logs
 * TASK_DELETE,         // To remove tasks from the system
 *
 * // --- Secret Management Permissions ---
 * SECRET_READ          // Specifically for accessing secrets (used by managers, admins, and explicitly denied for read-only users)
 * }
 */
@Component
public class CustomPermissionEvaluator implements PermissionEvaluator { // Corrected class name

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
            // Other permissions (like WORKFLOW, METADATA) are handled by specific roles (READ_ONLY_USER, WORKFLOW_MANAGER, etc.)
            // or by direct ownership if applicable to those models (which isn't implemented for them currently in this evaluator).
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
            // Explicitly deny secrets to READ_ONLY_USER as per requirement
            if ("SECRET".equals(targetType) && perm.equals(Permission.SECRET_READ.name())) {
                LOGGER.debug("Read-Only User '{}' denied access to SECRET.", currentUserId);
                return false;
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
            // User Workflow Manager also has access to Secrets
            if ("SECRET".equals(targetType) && perm.equals(Permission.SECRET_READ.name())) {
                LOGGER.debug("User Workflow Manager '{}' granted READ access to SECRET.", currentUserId);
                return true;
            }
            // Can manage queues for workflows
            if ("QUEUE".equals(targetType)) { // Assuming QUEUE operations
                LOGGER.debug("User Workflow Manager '{}' granted QUEUE access.", currentUserId);
                return perm.equals(Permission.QUEUE_READ.name()) ||
                        perm.equals(Permission.QUEUE_WRITE.name()) ||
                        perm.equals(Permission.QUEUE_DELETE.name()) ||
                        perm.equals(Permission.QUEUE_PURGE.name());
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
                        perm.equals(Permission.METADATA_CREATE.name());
            }
            // User Metadata Manager also has access to Secrets
            if ("SECRET".equals(targetType) && perm.equals(Permission.SECRET_READ.name())) {
                LOGGER.debug("User Metadata Manager '{}' granted READ access to SECRET.", currentUserId);
                return true;
            }
        }

        // --- Application Roles Specific Permissions ---

        // Worker: Can poll and execute tasks that it has Execute permissions for.
        if (authorities.contains(ApplicationRole.WORKER.withPrefix())) {
            if ("TASK".equals(targetType)) {
                LOGGER.debug("Worker app '{}' granted TASK access.", currentUserId);
                return perm.equals(Permission.TASK_POLL.name()) ||
                        perm.equals(Permission.TASK_UPDATE.name()) || // Workers update tasks after execution
                        perm.equals(Permission.TASK_LOG.name());     // Workers might add logs
            }
            // Could also be for WORKFLOW_EXECUTE if a worker can directly execute workflows
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
                LOGGER.debug("Unrestricted Worker app '{}' granted full access to Workflow.", currentUserId);
                return perm.equals(Permission.WORKFLOW_VIEW.name()) ||
                        perm.equals(Permission.WORKFLOW_EXECUTE.name()) ||
                        perm.equals(Permission.WORKFLOW_MODIFY.name()) ||
                        perm.equals(Permission.WORKFLOW_DELETE.name());
            }
            if ("TASK".equals(targetType)) {
                LOGGER.debug("Unrestricted Worker app '{}' granted full TASK access.", currentUserId);
                return perm.equals(Permission.TASK_POLL.name()) ||
                        perm.equals(Permission.TASK_READ.name()) ||
                        perm.equals(Permission.TASK_UPDATE.name()) ||
                        perm.equals(Permission.TASK_LOG.name()) ||
                        perm.equals(Permission.TASK_DELETE.name());
            }
            // Unrestricted Worker likely also has access to queues for polling
            if ("QUEUE".equals(targetType)) {
                LOGGER.debug("Unrestricted Worker app '{}' granted QUEUE access.", currentUserId);
                return perm.equals(Permission.QUEUE_READ.name()) ||
                        perm.equals(Permission.QUEUE_WRITE.name()) || // If they can push to queues
                        perm.equals(Permission.QUEUE_DELETE.name()) || // If they can remove from queues
                        perm.equals(Permission.QUEUE_PURGE.name()); // If they can purge queues
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
            // App Metadata Manager also has access to Secrets
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
            if ("TASK".equals(targetType) && perm.equals(Permission.WORKFLOW_POLL.name())) { // Or TASK_EXECUTE, TASK_READ, TASK_UPDATE
                LOGGER.debug("App Workflow Manager app '{}' granted POLL access to TASK.", currentUserId);
                return true;
            }
            // App Workflow Manager also has access to Secrets
            if ("SECRET".equals(targetType) && perm.equals(Permission.SECRET_READ.name())) {
                LOGGER.debug("App Workflow Manager app '{}' granted READ access to SECRET.", currentUserId);
                return true;
            }
            // Can manage queues
            if ("QUEUE".equals(targetType)) {
                LOGGER.debug("App Workflow Manager app '{}' granted QUEUE access.", currentUserId);
                return perm.equals(Permission.QUEUE_READ.name()) ||
                        perm.equals(Permission.QUEUE_WRITE.name()) ||
                        perm.equals(Permission.QUEUE_DELETE.name()) ||
                        perm.equals(Permission.QUEUE_PURGE.name());
            }
        }

        // APP_APPLICATION_MANAGER (Application Role): Can create, update, and delete any application.
        if (authorities.contains(ApplicationRole.APP_APPLICATION_MANAGER.withPrefix())) {
            if ("APPLICATION".equals(targetType)) {
                LOGGER.debug("App Application Manager app '{}' granted access to APPLICATION.", currentUserId);
                return perm.equals(Permission.APPLICATION_CREATE.name()) ||
                        perm.equals(Permission.APPLICATION_MANAGE.name()) ||
                        perm.equals(Permission.APPLICATION_READ.name());
            }
        }

        // General Admin permissions for AdminResource endpoint types (e.g., /admin/properties)
        if ("ADMIN".equals(targetType)) {
            LOGGER.debug("General ADMIN target type. Checking for ADMIN_READ/WRITE. User '{}' has authorities {}.", currentUserId, authorities);
            return perm.equals(Permission.ADMIN_READ.name()) || perm.equals(Permission.ADMIN_WRITE.name());
        }

        // Event Permissions
        if ("EVENT".equals(targetType)) {
            // For general event operations not tied to a specific user's resource
            LOGGER.debug("General EVENT target type. User '{}' has authorities {}.", currentUserId, authorities);
            return perm.equals(Permission.EVENT_CREATE.name()) ||
                    perm.equals(Permission.EVENT_READ.name()) ||
                    perm.equals(Permission.EVENT_UPDATE.name()) ||
                    perm.equals(Permission.EVENT_DELETE.name());
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
                // Assuming MyResource has a findById method that takes Long
                MyResource resource = myResourceService.findById((Long) targetId);
                if (resource == null) {
                    LOGGER.warn("MyResource with ID {} not found for permission check. Denying access.", targetId);
                    return false;
                }
                return hasPermission(authentication, resource, permission);
            case "WORKFLOW":
                // Assuming Workflow has a findById method that takes Long or String (adjust cast if needed)
                // For simplicity, assuming String ID for Workflow (as per Controller String workflowId)
                Workflow workflow = workflowService.findById((String) targetId); // Changed to String as per Conductor Workflow ID
                if (workflow == null) {
                    LOGGER.warn("Workflow with ID {} not found for permission check. Denying access.", targetId);
                    return false;
                }
                return hasPermission(authentication, workflow, permission);
            case "METADATA":
                // Assuming Metadata has a findById method that takes Long or String (adjust cast if needed)
                // For Metadata (TaskDef/WorkflowDef) names are usually String IDs
                // If your service uses Long, cast accordingly. For now, assuming String.
                Metadata metadata = metadataService.findById((String) targetId); // Assuming String ID for Metadata
                if (metadata == null) {
                    LOGGER.warn("Metadata with ID {} not found for permission check. Denying access.", targetId);
                    return false;
                }
                return hasPermission(authentication, metadata, permission);
            case "SECRET":
                // Secrets permission is typically based on roles, not individual secret ownership.
                // Rule 8: Secrets - Access for WORKFLOW_MANAGER, METADATA_MANAGER, ADMIN (EXCLUDES READ_ONLY_USER)
                // This is already checked in the object-level hasPermission. For ID-based, we assume if
                // a relevant role is present and not READ_ONLY_USER, access is granted.
                // No specific object needs to be fetched, as the permission is effectively role-based.
                return !authorities.contains(UserRole.READ_ONLY_USER.withPrefix());
            case "APPLICATION":
                // Application permission is based on roles, no specific object fetch by ID for this example
                return authorities.contains(ApplicationRole.APPLICATION_API.withPrefix()) ||
                        authorities.contains(ApplicationRole.APP_APPLICATION_MANAGER.withPrefix());
            case "TASK":
                // Task permission is based on roles or indirect workflow ownership. Fetching task object
                // to delegate might be an option, but for now, it's mostly role-based or already handled
                // by workflow permissions if the task is part of a workflow.
                // For direct Task ID access, this would require `taskService.getTask(taskId)` and then
                // delegating. Since that was not originally in the CustomPermissionEvaluator,
                // we'll keep it as role-based for simplicity for ID-based access for TASK.
                return authorities.contains(ApplicationRole.WORKER.withPrefix()) ||
                        authorities.contains(ApplicationRole.UNRESTRICTED_WORKER.withPrefix()) ||
                        authorities.contains(ApplicationRole.APP_WORKFLOW_MANAGER.withPrefix());
            case "EVENT":
                // Event permissions are generally for managing event handlers by roles, not specific event instances by ID.
                return authorities.contains(UserRole.ADMIN.withPrefix()) || authorities.contains(ApplicationRole.APP_ADMIN.withPrefix()) ||
                        authorities.contains(UserRole.METADATA_MANAGER.withPrefix()) || authorities.contains(ApplicationRole.METADATA_API.withPrefix()); // Event handlers are metadata
            case "QUEUE":
                // Queue permissions are generally role-based, not specific queue object by ID.
                return authorities.contains(UserRole.ADMIN.withPrefix()) || authorities.contains(ApplicationRole.APP_ADMIN.withPrefix()) ||
                        authorities.contains(UserRole.WORKFLOW_MANAGER.withPrefix()) || authorities.contains(ApplicationRole.APP_WORKFLOW_MANAGER.withPrefix()) ||
                        authorities.contains(ApplicationRole.WORKER.withPrefix()) || authorities.contains(ApplicationRole.UNRESTRICTED_WORKER.withPrefix());
            case "ADMIN":
                // Admin-specific permissions are directly role-based.
                return authorities.contains(UserRole.ADMIN.withPrefix()) || authorities.contains(ApplicationRole.APP_ADMIN.withPrefix());
            default:
                LOGGER.warn("Unknown target type '{}' for ID-based permission check. Denying access.", type);
                return false;
        }
    }
}