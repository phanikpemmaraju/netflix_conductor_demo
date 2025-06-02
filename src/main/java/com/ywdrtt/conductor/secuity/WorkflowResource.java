package com.ywdrtt.conductor.secuity;

// File: conductor-oss/conductor/conductor-e2c76e689bc15544255f39c815df05ee0cf7fc06/rest/src/main/java/com/netflix/conductor/rest/controllers/WorkflowResource.java
package com.netflix.conductor.rest.controllers;

import com.netflix.conductor.common.run.SearchResult;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.common.run.WorkflowSummary;
import com.netflix.conductor.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import org.springframework.security.access.prepost.PreAuthorize;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping(value = "/workflow")
@SecurityRequirement(name = "api_key")
public class WorkflowResource {

    private final WorkflowService workflowService;

    @Inject
    public WorkflowResource(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Operation(summary = "Start a new workflow with StartWorkflowRequest")
    @PostMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_CREATE.name())") // Create new workflow
    public String startWorkflow(@RequestBody StartWorkflowRequest request) {
        return workflowService.startWorkflow(request);
    }

    @Operation(summary = "Start a new workflow by name")
    @PostMapping(value = "/{name}", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_CREATE.name())") // Create new workflow
    public String startWorkflow(
            @PathVariable("name") String name,
            @RequestParam(value = "version", required = false) Integer version,
            @RequestParam(value = "correlationId", required = false) String correlationId,
            @Parameter(description = "Input to the workflow", required = true) @RequestBody Map<String, Object> input) {
        return workflowService.startWorkflow(name, version, correlationId, input);
    }

    @Operation(summary = "Get a workflow by workflow id")
    @GetMapping(value = "/{workflowId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(#workflowId, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_VIEW.name())")
    public Workflow getWorkflow(
            @PathVariable("workflowId") String workflowId,
            @RequestParam(value = "includeTasks", defaultValue = "true") boolean includeTasks) {
        return workflowService.getWorkflow(workflowId, includeTasks);
    }

    @Operation(summary = "Delete a workflow by workflow id")
    @DeleteMapping(value = "/{workflowId}")
    @PreAuthorize("hasPermission(#workflowId, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_DELETE.name())")
    public void deleteWorkflow(@PathVariable("workflowId") String workflowId,
                               @RequestParam(value = "archiveWorkflow", defaultValue = "true") boolean archiveWorkflow) {
        workflowService.deleteWorkflow(workflowId, archiveWorkflow);
    }

    @Operation(summary = "Get the list of workflows by correlation id")
    @GetMapping(value = "/correlationid/{correlationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_VIEW.name())") // View any workflow associated by correlationId
    public List<Workflow> getWorkflows(
            @PathVariable("correlationId") String correlationId,
            @RequestParam(value = "includeClosed", defaultValue = "false") boolean includeClosed,
            @RequestParam(value = "includeTasks", defaultValue = "false") boolean includeTasks) {
        return workflowService.getWorkflowsByCorrelationId(correlationId, includeClosed, includeTasks);
    }

    @Operation(summary = "Get the list of workflows by correlation id and workflow name")
    @GetMapping(value = "/correlationid/{name}/{correlationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_VIEW.name())") // View any workflow associated by correlationId and name
    public List<Workflow> getWorkflows(
            @PathVariable("name") String name,
            @PathVariable("correlationId") String correlationId,
            @RequestParam(value = "includeClosed", defaultValue = "false") boolean includeClosed,
            @RequestParam(value = "includeTasks", defaultValue = "false") boolean includeTasks) {
        return workflowService.getWorkflowsByCorrelationId(name, correlationId, includeClosed, includeTasks);
    }

    @Operation(summary = "Pause a workflow")
    @PutMapping(value = "/{workflowId}/pause")
    @PreAuthorize("hasPermission(#workflowId, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_MODIFY.name())")
    public void pauseWorkflow(@PathVariable("workflowId") String workflowId) {
        workflowService.pauseWorkflow(workflowId);
    }

    @Operation(summary = "Resume a workflow")
    @PutMapping(value = "/{workflowId}/resume")
    @PreAuthorize("hasPermission(#workflowId, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_MODIFY.name())")
    public void resumeWorkflow(@PathVariable("workflowId") String workflowId) {
        workflowService.resumeWorkflow(workflowId);
    }

    @Operation(summary = "Skip a task from a workflow")
    @PutMapping(value = "/{workflowId}/skiptask/{taskReferenceName}")
    @PreAuthorize("hasPermission(#workflowId, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_MODIFY.name())")
    public void skipTaskFromWorkflow(@PathVariable("workflowId") String workflowId,
                                     @PathVariable("taskReferenceName") String taskReferenceName) {
        workflowService.skipTaskFromWorkflow(workflowId, taskReferenceName);
    }

    @Operation(summary = "Rerun a workflow")
    @PostMapping(value = "/{workflowId}/rerun", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("hasPermission(#workflowId, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_EXECUTE.name())") // Rerun implies execution
    public String rerunWorkflow(
            @PathVariable("workflowId") String workflowId,
            @RequestBody(required = false) Map<String, Object> rerunWorkflowRequest) {
        return workflowService.rerun(rerunWorkflowRequest, workflowId);
    }

    @Operation(summary = "Restart a workflow")
    @PostMapping(value = "/{workflowId}/restart")
    @PreAuthorize("hasPermission(#workflowId, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_EXECUTE.name())") // Restart implies execution
    public void restart(@PathVariable("workflowId") String workflowId,
                        @RequestParam(value = "useLatestDefinitions", defaultValue = "false") boolean useLatestDefinitions) {
        workflowService.restart(workflowId, useLatestDefinitions);
    }

    @Operation(summary = "Terminate a workflow")
    @PostMapping(value = "/{workflowId}/terminate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#workflowId, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_DELETE.name())") // Terminate implies deletion
    public void terminate(@PathVariable("workflowId") String workflowId,
                          @RequestParam(value = "reason", required = false) String reason) {
        workflowService.terminateWorkflow(workflowId, reason);
    }

    @Operation(summary = "Search for workflows based on payload")
    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_VIEW.name())") // General search for workflows
    public SearchResult<WorkflowSummary> search(
            @RequestParam(value = "start", defaultValue = "0") int start,
            @RequestParam(value = "size", defaultValue = "100") int size,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "freeText", defaultValue = "*") String freeText,
            @RequestParam(value = "query", required = false) String query) {
        return workflowService.search(start, size, sort, freeText, query);
    }
}