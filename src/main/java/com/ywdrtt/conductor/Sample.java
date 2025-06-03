// src/main/java/com/netflix/conductor/security/workflowacl/service/WorkflowAuthorizationService.java
package com.netflix.conductor.security.workflowacl.service;

import com.netflix.conductor.dao.MetadataDAO; // Conductor's existing MetadataDAO
import com.netflix.conductor.model.WorkflowModel; // Needed to get workflow name/version from instance ID
import com.netflix.conductor.service.WorkflowService; // To fetch WorkflowModel by instance ID
import com.netflix.conductor.security.workflowacl.dao.AuthorizationDAO;
import com.netflix.conductor.security.workflowacl.model.PermissionLevel;
import com.netflix.conductor.security.workflowacl.model.WorkflowAclEntity;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef; // Used in definitions

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service("workflowAuthorization") // Named for @PreAuthorize SpEL expression access
public class WorkflowAuthorizationService {

    private final AuthorizationDAO authorizationDAO;
    private final MetadataDAO metadataDAO; // To look up workflow defs by name/version
    private final WorkflowService workflowService; // To look up workflow instances by ID

    @Autowired
    public WorkflowAuthorizationService(AuthorizationDAO authorizationDAO, MetadataDAO metadataDAO, WorkflowService workflowService) {
        this.authorizationDAO = authorizationDAO;
        this.metadataDAO = metadataDAO;
        this.workflowService = workflowService;
    }

    /**
     * Grants a specific permission level for a workflow definition to a subject.
     * Called by your custom admin API or internally when a workflow is created.
     */
    public void grantPermission(Long workflowDefId, String subjectType, String subjectId, PermissionLevel permissionLevel, String grantedByUserId) {
        WorkflowAclEntity aclEntry = new WorkflowAclEntity(workflowDefId, subjectType, subjectId, permissionLevel, grantedByUserId);
        authorizationDAO.insertPermission(aclEntry);
    }

    /**
     * Checks if the current authenticated subject (user, group, or role) has the required permission
     * for a workflow definition specified by name and version.
     * This method is called by @PreAuthorize.
     */
    public boolean hasPermission(String workflowDefName, Integer workflowDefVersion, PermissionLevel requiredPermission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        String currentSubjectId;
        List<String> currentSubjectGroups;
        List<String> currentSubjectRoles;

        if (authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            currentSubjectId = jwt.getSubject();
            currentSubjectGroups = extractGroupsFromJwt(jwt);
            currentSubjectRoles = extractRolesFromJwt(jwt);
        } else {
            currentSubjectId = authentication.getName();
            currentSubjectGroups = extractGroupsFromAuthorities(authentication);
            currentSubjectRoles = extractRolesFromAuthorities(authentication);
        }

        Optional<Long> workflowDefIdOptional = authorizationDAO.getWorkflowDefIdByNameAndVersion(workflowDefName, workflowDefVersion);

        if (workflowDefIdOptional.isEmpty()) {
            return false; // Workflow definition not found, deny by default
        }
        Long workflowDefId = workflowDefIdOptional.get();

        // 1. Check direct user permissions
        if (authorizationDAO.checkDirectPermission(workflowDefId, "USER", currentSubjectId, requiredPermission)) {
            return true;
        }

        // 2. Check group permissions
        for (String groupId : currentSubjectGroups) {
            if (authorizationDAO.checkDirectPermission(workflowDefId, "GROUP", groupId, requiredPermission)) {
                return true;
            }
        }

        // 3. Check role permissions (these are also typically from JWT claims)
        for (String roleId : currentSubjectRoles) {
            if (authorizationDAO.checkDirectPermission(workflowDefId, "ROLE", roleId, requiredPermission)) {
                return true;
            }
        }

        return false;
    }

    /**
     * @PreAuthorize helper for checking permission to create a workflow.
     * If the workflow definition already exists, it checks EXECUTE permission.
     * If it's a new definition, it checks a broad 'CREATE_WORKFLOW_DEF' role.
     */
    public boolean canCreateWorkflow(String workflowDefName, Integer workflowDefVersion) {
        // If workflow definition already exists, check EXECUTE permission (to start an instance)
        if (authorizationDAO.getWorkflowDefIdByNameAndVersion(workflowDefName, workflowDefVersion).isPresent()) {
            return hasPermission(workflowDefName, workflowDefVersion, PermissionLevel.EXECUTE);
        }

        // If workflow definition does not exist (new creation), check if the user has a general "CREATE_WORKFLOW_DEF" role
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        List<String> currentSubjectRoles = Collections.emptyList();
        if (authentication.getPrincipal() instanceof Jwt) {
            currentSubjectRoles = extractRolesFromJwt((Jwt) authentication.getPrincipal());
        } else {
            currentSubjectRoles = extractRolesFromAuthorities(authentication);
        }
        // This assumes 'CREATE_WORKFLOW_DEF' is a specific role in Keycloak for creating new definitions
        return currentSubjectRoles.contains("CREATE_WORKFLOW_DEF");
    }

    /**
     * @PreAuthorize helper for checking permission to read (view) a workflow instance.
     * Derives workflow definition details from the instance ID and checks READ permission.
     */
    public boolean canReadWorkflow(String workflowInstanceId) {
        try {
            // Fetch the workflow instance to get its definition name and version
            // This might involve calling WorkflowService.getWorkflow(workflowInstanceId, false)
            // and then inspecting the Workflow object.
            // For this example, we assume workflowService can get WorkflowDef for the instance.
            com.netflix.conductor.common.run.Workflow workflow = workflowService.getWorkflow(workflowInstanceId, false);
            if (workflow == null) return false;

            return hasPermission(workflow.getWorkflowName(), workflow.getWorkflowVersion(), PermissionLevel.READ);
        } catch (Exception e) {
            // Log the error (e.g., workflow not found, permission check failed)
            System.err.println("Error checking read permission for workflow instance " + workflowInstanceId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * @PreAuthorize helper for checking permission to update a workflow definition.
     */
    public boolean canUpdateWorkflowDef(String workflowDefName, Integer workflowDefVersion) {
        return hasPermission(workflowDefName, workflowDefVersion, PermissionLevel.UPDATE);
    }

    /**
     * @PreAuthorize helper for checking permission to delete a workflow definition.
     */
    public boolean canDeleteWorkflowDef(String workflowDefName, Integer workflowDefVersion) {
        return hasPermission(workflowDefName, workflowDefVersion, PermissionLevel.DELETE);
    }

    /**
     * @PreAuthorize helper for checking permission to terminate a workflow instance.
     */
    public boolean canTerminateWorkflow(String workflowInstanceId) {
        try {
            com.netflix.conductor.common.run.Workflow workflow = workflowService.getWorkflow(workflowInstanceId, false);
            if (workflow == null) return false;
            return hasPermission(workflow.getWorkflowName(), workflow.getWorkflowVersion(), PermissionLevel.DELETE); // Terminate usually requires DELETE or a specific "TERMINATE" permission
        } catch (Exception e) {
            System.err.println("Error checking terminate permission for workflow instance " + workflowInstanceId + ": " + e.getMessage());
            return false;
        }
    }


    // --- JWT Claim Extraction Helpers (crucial for Keycloak integration) ---
    // Ensure these correctly extract roles/groups from your Keycloak JWT structure.
    private List<String> extractGroupsFromJwt(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList("groups"); // Common claim for groups from Keycloak
        if (groups == null && jwt.hasClaim("realm_access")) { // Fallback to realm_access roles if groups not found
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess != null && realmAccess.containsKey("roles")) {
                groups = (List<String>) realmAccess.get("roles");
            }
        }
        return groups != null ? groups : Collections.emptyList();
    }

    private List<String> extractRolesFromJwt(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("scope"); // Often scopes are directly roles
        if (roles == null && jwt.hasClaim("realm_access")) { // Fallback to realm_access roles
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess != null && realmAccess.containsKey("roles")) {
                roles = (List<String>) realmAccess.get("roles");
            }
        }
        if (roles == null && jwt.hasClaim("resource_access")) { // Check client-specific roles
            Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
            // Replace 'your-client-id' with the actual client ID configured in Keycloak
            if (resourceAccess != null && resourceAccess.containsKey("your-client-id")) {
                Map<String, Object> clientRoles = (Map<String, Object>) resourceAccess.get("your-client-id");
                if (clientRoles != null && clientRoles.containsKey("roles")) {
                    List<String> clientSpecificRoles = (List<String>) clientRoles.get("roles");
                    if (roles == null) roles = new ArrayList<>(); // Initialize if null
                    roles.addAll(clientSpecificRoles);
                }
            }
        }
        return roles != null ? roles : Collections.emptyList();
    }

    // Fallback if principal is not Jwt, or if custom UserDetails populates authorities
    private List<String> extractGroupsFromAuthorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("GROUP_")) // Assuming Spring Security prefixes groups
                .map(a -> a.substring("GROUP_".length()))
                .collect(Collectors.toList());
    }

    private List<String> extractRolesFromAuthorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_") || a.startsWith("SCOPE_")) // Assuming Spring Security prefixes roles/scopes
                .map(a -> a.substring(a.indexOf("_") + 1))
                .collect(Collectors.toList());
    }
}





// Modified com.netflix.conductor.rest.controllers.MetadataResource.java
// (Conceptual modifications)
package com.netflix.conductor.rest.controllers;

import com.netflix.conductor.service.MetadataService;
import com.netflix.conductor.security.workflowacl.service.WorkflowAuthorizationService; // Your custom service
import com.netflix.conductor.security.workflowacl.model.PermissionLevel; // Import enum
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.client.exception.ConductorClientException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping(value = RequestMappingConstants.METADATA_API_PATH)
public class MetadataResource {

    private final MetadataService metadataService;
    private final WorkflowAuthorizationService workflowAuthorizationService;

    @Autowired
    public MetadataResource(MetadataService metadataService, WorkflowAuthorizationService workflowAuthorizationService) {
        this.metadataService = metadataService;
        this.workflowAuthorizationService = workflowAuthorizationService;
    }

    /**
     * Register a new workflow definition or update an existing one.
     * The @PreAuthorize checks if the user has permission to perform this action.
     * After successful registration/update, it ensures owner permissions are set in ACL.
     */
    @PostMapping(value = "/workflow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    // Assuming 'canCreateWorkflow' check will verify if it's a new creation or requires 'EXECUTE' for existing.
    @PreAuthorize("@workflowAuthorization.canCreateWorkflow(#workflowDef.name, #workflowDef.version)")
    public void registerWorkflowDef(@RequestBody WorkflowDef workflowDef) {
        // 1. Get current authenticated user details from Keycloak JWT
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String currentSubjectId;
        // The principal should be Jwt due to Keycloak integration
        if (authentication.getPrincipal() instanceof Jwt) {
            currentSubjectId = ((Jwt) authentication.getPrincipal()).getSubject();
        } else {
            currentSubjectId = authentication.getName(); // Fallback if principal type is different
        }

        // 2. Perform the actual workflow definition registration
        // This is the original Conductor OSS logic that saves/updates the workflowDef in meta_workflow_def
        metadataService.registerWorkflowDef(workflowDef);

        // 3. Add permission entries to workflow_acl for the creator/owner
        Long workflowDefId = workflowAuthorizationService.getWorkflowDefId(workflowDef.getName(), workflowDef.getVersion());

        if (workflowDefId != null) {
            // Grant creator all permissions by default (Owner)
            workflowAuthorizationService.grantPermission(workflowDefId, "USER", currentSubjectId, PermissionLevel.CREATE, currentSubjectId);
            workflowAuthorizationService.grantPermission(workflowDefId, "USER", currentSubjectId, PermissionLevel.READ, currentSubjectId);
            workflowAuthorizationService.grantPermission(workflowDefId, "USER", currentSubjectId, PermissionLevel.UPDATE, currentSubjectId);
            workflowAuthorizationService.grantPermission(workflowDefId, "USER", currentSubjectId, PermissionLevel.DELETE, currentSubjectId);
            workflowAuthorizationService.grantPermission(workflowDefId, "USER", currentSubjectId, PermissionLevel.EXECUTE, currentSubjectId);

            // You might add logic here to grant default permissions to groups/roles
            // For example, if a "workflow-creators" group automatically gets READ access
            // List<String> currentSubjectGroups = extractGroupsFromJwt((Jwt)authentication.getPrincipal());
            // if (currentSubjectGroups.contains("workflow-creators")) {
            //    workflowAuthorizationService.grantPermission(workflowDefId, "GROUP", "workflow-creators", PermissionLevel.READ, currentSubjectId);
            // }

        } else {
            // This scenario should ideally not happen if registerWorkflowDef was successful
            throw new ConductorClientException("Internal Error: Workflow definition ID not found for ACL setup after creation.");
        }
    }

    /**
     * Update an existing workflow definition.
     * Requires 'UPDATE' permission on the specific workflow definition.
     */
    @PutMapping(value = "/workflow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@workflowAuthorization.canUpdateWorkflowDef(#workflowDef.name, #workflowDef.version)")
    public void updateWorkflowDef(@RequestBody WorkflowDef workflowDef) {
        metadataService.updateWorkflowDefs(Collections.singletonList(workflowDef));
        // No need to re-add owner permissions here, as they should already exist.
        // If updating owner, that would be a separate ACL management API.
    }

    /**
     * Get a workflow definition by name and version.
     * Requires 'READ' permission.
     */
    @GetMapping(value = "/workflow/{name}/{version}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("@workflowAuthorization.hasPermission(#name, #version, T(com.netflix.conductor.security.workflowacl.model.PermissionLevel).READ)")
    public WorkflowDef getWorkflowDef(
            @PathVariable("name") String name,
            @PathVariable("version") Integer version
    ) {
        return metadataService.getWorkflowDef(name, version);
    }

    /**
     * Delete a workflow definition.
     * Requires 'DELETE' permission.
     */
    @DeleteMapping(value = "/workflow/{name}/{version}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@workflowAuthorization.hasPermission(#name, #version, T(com.netflix.conductor.security.workflowacl.model.PermissionLevel).DELETE)")
    public void unregisterWorkflowDef(
            @PathVariable("name") String name,
            @PathVariable("version") Integer version
    ) {
        metadataService.unregisterWorkflowDef(name, version);
        // Optional: Also delete all related ACL entries from workflow_acl table
        // authorizationService.deleteAllPermissionsForWorkflowDef(workflowDefId);
    }

    // --- JWT Claim Extraction Helpers (copy from WorkflowAuthorizationService or a shared utility) ---
    private List<String> extractGroupsFromJwt(Jwt jwt) { /* ... */ return Collections.emptyList(); }
    private List<String> extractRolesFromJwt(Jwt jwt) { /* ... */ return Collections.emptyList(); }
    private List<String> extractGroupsFromAuthorities(Authentication authentication) { /* ... */ return Collections.emptyList(); }
    private List<String> extractRolesFromAuthorities(Authentication authentication) { /* ... */ return Collections.emptyList(); }
}



++++++++


// Modified com.netflix.conductor.rest.controllers.WorkflowResource.java
// (Conceptual modifications)
        package com.netflix.conductor.rest.controllers;

import com.netflix.conductor.service.WorkflowService;
import com.netflix.conductor.security.workflowacl.service.WorkflowAuthorizationService; // Your custom service
import com.netflix.conductor.security.workflowacl.model.PermissionLevel; // Import enum
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import com.netflix.conductor.common.run.Workflow; // Used for Workflow instances

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

// ... other imports ...

@RestController
@RequestMapping(value = RequestMappingConstants.WORKFLOW_API_PATH)
public class WorkflowResource {

    private final WorkflowService workflowService;
    private final WorkflowAuthorizationService workflowAuthorizationService; // Renamed for clarity

    @Autowired
    public WorkflowResource(WorkflowService workflowService, WorkflowAuthorizationService workflowAuthorizationService) {
        this.workflowService = workflowService;
        this.workflowAuthorizationService = workflowAuthorizationService;
    }

    /**
     * Start a new workflow instance.
     * Requires 'EXECUTE' permission on the specific workflow definition.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("@workflowAuthorization.hasPermission(#startWorkflowRequest.name, #startWorkflowRequest.version, T(com.netflix.conductor.security.workflowacl.model.PermissionLevel).EXECUTE)")
    public String startWorkflow(@RequestBody StartWorkflowRequest startWorkflowRequest) {
        return workflowService.startWorkflow(startWorkflowRequest);
    }

    /**
     * Retrieve a workflow instance by workflow ID.
     * Requires 'READ' permission on the workflow definition associated with the instance.
     */
    @GetMapping(value = "/{workflowId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("@workflowAuthorization.canReadWorkflow(#workflowId)") // Custom helper method in service
    public Workflow getWorkflow(
            @PathVariable("workflowId") String workflowId,
            @RequestParam(value = "includeTasks", defaultValue = "true") boolean includeTasks
    ) {
        return workflowService.getWorkflow(workflowId, includeTasks);
    }

    /**
     * Terminate a workflow instance.
     * Requires 'DELETE' permission on the workflow definition associated with the instance.
     */
    @DeleteMapping(value = "/{workflowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@workflowAuthorization.canTerminateWorkflow(#workflowId)") // Custom helper method in service
    public void terminateWorkflow(
            @PathVariable("workflowId") String workflowId,
            @RequestParam(value = "reason", required = false) String reason
    ) {
        workflowService.terminateWorkflow(workflowId, reason);
    }

    // You would continue applying @PreAuthorize to other methods in WorkflowResource
    // that involve interacting with specific workflow instances or definitions.
    // Example: pauseWorkflow, resumeWorkflow, rerunWorkflow, retryLastFailedTask etc.

    // ... other controller methods ...
}