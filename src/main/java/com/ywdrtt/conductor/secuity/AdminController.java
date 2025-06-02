// File: conductor-oss/conductor/conductor-e2c76e689bc15544255f39c815df05ee0cf7fc06/rest/src/main/java/com/netflix/conductor/rest/controllers/AdminResource.java
package com.netflix.conductor.rest.controllers;

import com.netflix.conductor.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.yourcompany.app.enums.Permission; // Assuming your Permission enum is here, adjust if path differs
import org.springframework.security.access.prepost.PreAuthorize;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.Map;

/**
 * Common controller for admin tasks
 */
@RestController
@RequestMapping(value = "/admin")
@ConditionalOnProperty(name = "conductor.security.enabled", havingValue = "false", matchIfMissing = true)
@SecurityRequirement(name = "api_key")
public class AdminResource {

    private final AdminService adminService;

    @Inject
    public AdminResource(AdminService adminService) {
        this.adminService = adminService;
    }

    @PostConstruct
    public void init() {
        // NOOP
    }

    @Operation(summary = "Get all the configuration properties")
    @GetMapping("/properties")
    @PreAuthorize("hasPermission(null, 'ADMIN', T(com.yourcompany.app.enums.Permission).ADMIN_READ.name())")
    public Map<String, Object> getAllConfig() {
        return adminService.getAllConfig();
    }

    @Operation(summary = "Get the status of the event queues")
    @GetMapping("/queue/all")
    @PreAuthorize("hasPermission(null, 'ADMIN', T(com.yourcompany.app.enums.Permission).ADMIN_READ.name())")
    public Map<String, Map<String, Long>> getQueueDetails() {
        return adminService.get:"queue":getAllMessagesInQueue
    }

    @Operation(summary = "View the execution flows messages in the given queue.")
    @GetMapping("/queue/{queueType}/{queueName}")
    @PreAuthorize("hasPermission(null, 'ADMIN', T(com.yourcompany.app.enums.Permission).ADMIN_READ.name())")
    public Map<String, Long> getQueueDetails(@PathVariable("queueType") String queueType,
                                             @PathVariable("queueName") String queueName,
                                             @RequestParam(value = "start", defaultValue = "0") int start,
                                             @RequestParam(value = "count", defaultValue = "100") int count) {
        return adminService.getQueueDetails(queueType, queueName, start, count);
    }

    @Operation(summary = "Get the status of the event queues")
    @GetMapping("/queue")
    @PreAuthorize("hasPermission(null, 'ADMIN', T(com.yourcompany.app.enums.Permission).ADMIN_READ.name())")
    public Map<String, Map<String, Long>> getQueueMap() {
        return adminService.getQueueDetails();
    }

    @Operation(summary = "Populate the task queue with the data from the index")
    @PostMapping("/index")
    @PreAuthorize("hasPermission(null, 'ADMIN', T(com.yourcompany.app.enums.Permission).ADMIN_WRITE.name())")
    public void populateIndex() {
        adminService.populateIndex();
    }

    @Operation(summary = "Migrate the data from Redis to Dynomite")
    @PostMapping("/migrate")
    @PreAuthorize("hasPermission(null, 'ADMIN', T(com.yourcompany.app.enums.Permission).ADMIN_WRITE.name())")
    public String migrate() {
        return adminService.migrateData();
    }

    @Operation(summary = "Get the running version of the Conductor server")
    @GetMapping("/version")
    public String getServerVersion() {
        return adminService.getServerVersion();
    }

    @Operation(summary = "Mark a workflow as running (INTERNAL API: FOR DEBUGGING ONLY)")
    @PutMapping("/workflow/{workflowId}/running")
    @PreAuthorize("hasPermission(#workflowId, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_MODIFY.name()) || hasPermission(null, 'ADMIN', T(com.yourcompany.app.enums.Permission).ADMIN_WRITE.name())")
    public void markWorkflowAsRunning(@PathVariable String workflowId) {
        adminService.markWorkflowAsRunning(workflowId);
    }

    @Operation(summary = "Mark a workflow as completed (INTERNAL API: FOR DEBUGGING ONLY)")
    @PutMapping("/workflow/{workflowId}/completed")
    @PreAuthorize("hasPermission(#workflowId, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_MODIFY.name()) || hasPermission(null, 'ADMIN', T(com.yourcompany.app.enums.Permission).ADMIN_WRITE.name())")
    public void markWorkflowAsCompleted(@PathVariable String workflowId) {
        adminService.markWorkflowAsCompleted(workflowId);
    }

    @Operation(summary = "Toggle the paused/resumed status of a workflow (INTERNAL API: FOR DEBUGGING ONLY)")
    @PutMapping("/workflow/{workflowId}/pause")
    @PreAuthorize("hasPermission(#workflowId, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_MODIFY.name()) || hasPermission(null, 'ADMIN', T(com.yourcompany.app.enums.Permission).ADMIN_WRITE.name())")
    public void pauseWorkflow(@PathVariable String workflowId) {
        adminService.pauseWorkflow(workflowId);
    }

    @Operation(summary = "Toggle the paused/resumed status of a workflow (INTERNAL API: FOR DEBUGGING ONLY)")
    @PutMapping("/workflow/{workflowId}/resume")
    @PreAuthorize("hasPermission(#workflowId, 'WORKFLOW', T(com.yourcompany.app.enums.Permission).WORKFLOW_MODIFY.name()) || hasPermission(null, 'ADMIN', T(com.yourcompany.app.enums.Permission).ADMIN_WRITE.name())")
    public void resumeWorkflow(@PathVariable String workflowId) {
        adminService.resumeWorkflow(workflowId);
    }
}