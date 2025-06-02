package com.ywdrtt.conductor.secuity;

// File: conductor-oss/conductor/conductor-e2c76e689bc15544255f39c815df05ee0cf7fc06/rest/src/main/java/com/netflix/conductor/rest/controllers/WorkflowBulkResource.java
package com.netflix.conductor.rest.controllers;

import com.netflix.conductor.common.model.BulkResponse;
import com.netflix.conductor.service.WorkflowBulkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.yourcompany.app.enums.Permission; // Assuming your Permission enum is here, adjust if path differs
import org.springframework.security.access.prepost.PreAuthorize;

import javax.inject.Inject;
import java.util.List;

@RestController
@RequestMapping(value = "/workflow/bulk")
@SecurityRequirement(name = "api_key")
public class WorkflowBulkResource {

    private final WorkflowBulkService workflowBulkService;

    @Inject
    public WorkflowBulkResource(WorkflowBulkService workflowBulkService) {
        this.workflowBulkService = workflowBulkService;
    }

    @Operation(summary = "Pause a list of workflows")
    @PutMapping(value = "/pause", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_BULK_ACTION.name())") // Bulk actions are general `WORKFLOW_BULK_ACTION`
    public BulkResponse pauseWorkflow(@Parameter(description = "List of workflow Ids to be paused") @RequestParam("workflowIds") List<String> workflowIds) {
        return workflowBulkService.pauseWorkflow(workflowIds);
    }

    @Operation(summary = "Resume a list of workflows")
    @PutMapping(value = "/resume", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_BULK_ACTION.name())")
    public BulkResponse resumeWorkflow(@Parameter(description = "List of workflow Ids to be resumed") @RequestParam("workflowIds") List<String> workflowIds) {
        return workflowBulkService.resumeWorkflow(workflowIds);
    }

    @Operation(summary = "Retry a list of workflows")
    @PutMapping(value = "/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_BULK_ACTION.name())")
    public BulkResponse retryWorkflow(@Parameter(description = "List of workflow Ids to be retried") @RequestParam("workflowIds") List<String> workflowIds) {
        return workflowBulkService.retryWorkflow(workflowIds);
    }

    @Operation(summary = "Restart a list of workflows")
    @PutMapping(value = "/restart", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_BULK_ACTION.name())")
    public BulkResponse restartWorkflow(@Parameter(description = "List of workflow Ids to be restarted") @RequestParam("workflowIds") List<String> workflowIds) {
        return workflowBulkService.restartWorkflow(workflowIds);
    }

    @Operation(summary = "Terminate a list of workflows")
    @DeleteMapping(value = "/terminate", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_BULK_ACTION.name())")
    public BulkResponse terminateWorkflow(@Parameter(description = "List of workflow Ids to be terminated") @RequestParam("workflowIds") List<String> workflowIds,
                                          @RequestParam(value = "reason", required = false) String reason) {
        return workflowBulkService.terminateWorkflow(workflowIds, reason);
    }
}