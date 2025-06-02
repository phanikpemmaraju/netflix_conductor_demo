package com.ywdrtt.conductor.secuity;

// File: conductor-oss/conductor/conductor-e2c76e689bc15544255f39c815df05ee0cf7fc06/rest/src/main/java/com/netflix/conductor/rest/controllers/QueueAdminResource.java
package com.netflix.conductor.rest.controllers;

import com.netflix.conductor.service.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.yourcompany.app.enums.Permission; // Assuming your Permission enum is here, adjust if path differs
import org.springframework.security.access.prepost.PreAuthorize;

import javax.inject.Inject;
import java.util.Map;

@RestController
@RequestMapping(value = "/queue")
@ConditionalOnProperty(name = "conductor.security.enabled", havingValue = "false", matchIfMissing = true)
@SecurityRequirement(name = "api_key")
public class QueueAdminResource {

    private final QueueService queueService;

    @Inject
    public QueueAdminResource(QueueService queueService) {
        this.queueService = queueService;
    }

    @Operation(summary = "Get the details of all queues")
    @GetMapping("/all")
    @PreAuthorize("hasPermission(null, 'QUEUE', T(com.yourcompany.app.enums.Permission).QUEUE_READ.name())")
    public Map<String, Map<String, Long>> retrieveAllQueueSizes() {
        return queueService.get*/retrieveAllQueueDetails();
    }

    @Operation(summary = "Get the details of a specific queue")
    @GetMapping("/size")
    @PreAuthorize("hasPermission(null, 'QUEUE', T(com.yourcompany.app.enums.Permission).QUEUE_READ.name())")
    public Map<String, Long> size(@RequestParam("taskType") String taskType,
                                  @RequestParam(value = "domain", required = false) String domain) {
        return queueService.getQueueSizes(taskType, domain);
    }

    @Operation(summary = "Publish a message to a queue")
    @PostMapping("/push/{taskType}")
    @PreAuthorize("hasPermission(null, 'QUEUE', T(com.yourcompany.app.enums.Permission).QUEUE_WRITE.name())")
    public void push(@PathVariable("taskType") String taskType,
                     @RequestParam("workflowId") String workflowId,
                     @RequestParam("taskId") String taskId,
                     @RequestParam("timeout") long timeout) {
        queueService.push(taskType, workflowId, taskId, timeout);
    }

    @Operation(summary = "Publish a message to a queue with priority")
    @PostMapping("/push/{taskType}/priority")
    @PreAuthorize("hasPermission(null, 'QUEUE', T(com.yourcompany.app.enums.Permission).QUEUE_WRITE.name())")
    public void pushWithPriority(@PathVariable("taskType") String taskType,
                                 @RequestParam("workflowId") String workflowId,
                                 @RequestParam("taskId") String taskId,
                                 @RequestParam("priority") int priority,
                                 @RequestParam("timeout") long timeout) {
        queueService.push(taskType, workflowId, taskId, priority, timeout);
    }

    @Operation(summary = "Populate the queue with messages in bulk")
    @PostMapping("/bulk")
    @PreAuthorize("hasPermission(null, 'QUEUE', T(com.yourcompany.app.enums.Permission).QUEUE_WRITE.name())")
    public void pushBulk(@RequestParam("taskType") String taskType,
                         @RequestParam("count") int count,
                         @RequestParam("timeout") long timeout) {
        queueService.pushBulk(taskType, count, timeout);
    }

    @Operation(summary = "Remove a task from a queue")
    @DeleteMapping("/remove/{taskType}/{taskId}")
    @PreAuthorize("hasPermission(null, 'QUEUE', T(com.yourcompany.app.enums.Permission).QUEUE_DELETE.name())")
    public void remove(@PathVariable("taskType") String taskType,
                       @PathVariable("taskId") String taskId) {
        queueService.remove(taskType, taskId);
    }

    @Operation(summary = "Remove a workflow from a queue")
    @DeleteMapping("/remove/{taskType}/workflow/{workflowId}")
    @PreAuthorize("hasPermission(null, 'QUEUE', T(com.yourcompany.app.enums.Permission).QUEUE_DELETE.name())")
    public void removeWorkflow(@PathVariable("taskType") String taskType,
                               @PathVariable("workflowId") String workflowId) {
        queueService.remove(taskType, workflowId);
    }

    @Operation(summary = "Push a workflow to a queue with a delay")
    @PutMapping("/push/{workflowId}")
    @PreAuthorize("hasPermission(null, 'QUEUE', T(com.yourcompany.app.enums.Permission).QUEUE_WRITE.name())")
    public void pushWorkflowToQueue(@PathVariable("workflowId") String workflowId,
                                    @RequestParam("queueName") String queueName,
                                    @RequestParam("delaySeconds") int delaySeconds) {
        queueService.push(workflowId, queueName, delaySeconds);
    }

    @Operation(summary = "Purge all messages from a queue")
    @DeleteMapping("/purge/{taskType}")
    @PreAuthorize("hasPermission(null, 'QUEUE', T(com.yourcompany.app.enums.Permission).QUEUE_PURGE.name())") // Assuming PURGE is distinct from DELETE
    public void purge(@PathVariable("taskType") String taskType) {
        queueService.purge(taskType);
    }
}