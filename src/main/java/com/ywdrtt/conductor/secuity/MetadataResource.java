package com.ywdrtt.conductor.secuity;

// File: conductor-oss/conductor/conductor-e2c76e689bc15544255f39c815df05ee0cf7fc06/rest/src/main/java/com/netflix/conductor/rest/controllers/MetadataResource.java
package com.netflix.conductor.rest.controllers;

import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.service.MetadataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.yourcompany.app.enums.Permission; // Assuming your Permission enum is here, adjust if path differs
import org.springframework.security.access.prepost.PreAuthorize;

import javax.inject.Inject;
import java.util.List;

@RestController
@RequestMapping(value = "/metadata")
@SecurityRequirement(name = "api_key")
public class MetadataResource {

    private final MetadataService metadataService;

    @Inject
    public MetadataResource(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    // --- Task Definition Endpoints ---

    @Operation(summary = "Register a new task definition")
    @PostMapping(value = "/taskdef", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'METADATA', T(com.yourcompany.app.enums.Permission).METADATA_CREATE.name())")
    public void registerTaskDef(@RequestBody TaskDef taskDef) {
        metadataService.registerTaskDef(taskDef);
    }

    @Operation(summary = "Update an existing task definition")
    @PutMapping(value = "/taskdef", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(#taskDef.name, 'METADATA', T(com.yourcompany.app.enums.Permission).METADATA_UPDATE.name())") // Check by task name, or fetch and check by ID if available
    public void updateTaskDef(@RequestBody TaskDef taskDef) {
        // For taskDef update, the CustomPermissionEvaluator hasPermission(ID, TYPE, PERM) needs
        // to be able to resolve a TaskDef by its name to get its ID, or you might need a
        // hasPermission(null, 'METADATA', PERM) and rely on role access for all updates.
        // Assuming your MetadataService.findByName is used in evaluator if targetId is a string name.
        metadataService.updateTaskDef(taskDef);
    }

    @Operation(summary = "Get a task definition")
    @GetMapping(value = "/taskdef/{taskdefName}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(#taskdefName, 'METADATA', T(com.yourcompany.app.enums.Permission).METADATA_READ.name())")
    public TaskDef getTaskDef(@PathVariable("taskdefName") String taskdefName) {
        return metadataService.getTaskDef(taskdefName);
    }

    @Operation(summary = "Get all task definitions")
    @GetMapping(value = "/taskdef", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'METADATA', T(com.yourcompany.app.enums.Permission).METADATA_READ.name())")
    public List<TaskDef> getAllTaskDefs() {
        return metadataService.getAllTaskDefs();
    }

    @Operation(summary = "Remove a task definition")
    @DeleteMapping(value = "/taskdef/{taskdefName}")
    @PreAuthorize("hasPermission(#taskdefName, 'METADATA', T(com.yourcompany.app.enums.Permission).METADATA_DELETE.name())")
    public void unregisterTaskDef(@PathVariable("taskdefName") String taskdefName) {
        metadataService.unregisterTaskDef(taskdefName);
    }

    // --- Workflow Definition Endpoints ---

    @Operation(summary = "Register a new workflow definition")
    @PostMapping(value = "/workflowdef", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'METADATA', T(com.yourcompany.app.enums.Permission).METADATA_CREATE.name())")
    public void registerWorkflowDef(@RequestBody WorkflowDef workflowDef) {
        metadataService.registerWorkflowDef(workflowDef);
    }

    @Operation(summary = "Update an existing workflow definition")
    @PutMapping(value = "/workflowdef", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(#workflowDef.name, 'METADATA', T(com.yourcompany.app.enums.Permission).METADATA_UPDATE.name())") // Check by workflow name, or fetch and check by ID if available
    public void updateWorkflowDef(@RequestBody WorkflowDef workflowDef) {
        // Similar to taskDef, assuming name can be resolved or general METADATA_UPDATE permission is allowed.
        metadataService.updateWorkflowDef(workflowDef);
    }

    @Operation(summary = "Get a workflow definition")
    @GetMapping(value = "/workflowdef/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(#name, 'METADATA', T(com.yourcompany.app.enums.Permission).METADATA_READ.name())")
    public WorkflowDef getWorkflowDef(@PathVariable("name") String name,
                                      @RequestParam(value = "version", required = false) Integer version) {
        return metadataService.getWorkflowDef(name, version);
    }

    @Operation(summary = "Get all workflow definitions (latest versions only)")
    @GetMapping(value = "/workflowdef", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'METADATA', T(com.yourcompany.app.enums.Permission).METADATA_READ.name())")
    public List<WorkflowDef> getAllWorkflowDefs() {
        return metadataService.getAllWorkflowDefs();
    }

    @Operation(summary = "Remove a workflow definition")
    @DeleteMapping(value = "/workflowdef/{name}/{version}")
    @PreAuthorize("hasPermission(#name, 'METADATA', T(com.yourcompany.app.enums.Permission).METADATA_DELETE.name())")
    public void unregisterWorkflowDef(@PathVariable("name") String name, @PathVariable("version") int version) {
        metadataService.unRegisterWorkflowDef(name, version);
    }
}