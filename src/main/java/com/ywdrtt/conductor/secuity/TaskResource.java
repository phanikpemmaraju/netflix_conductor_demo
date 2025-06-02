package com.ywdrtt.conductor.secuity;
// File: conductor-oss/conductor/conductor-e2c76e689bc15544255f39c815df05ee0cf7fc06/rest/src/main/java/com/netflix/conductor/rest/controllers/TaskResource.java
package com.netflix.conductor.rest.controllers;

import com.netflix.conductor.common.metadata.tasks.PollData;
import com.netflix.conductor.common.metadata.tasks.TaskExecLog;
import com.netflix.conductor.common.run.TaskSummary;
import com.netflix.conductor.service.TaskService;
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
import java.util.Map;

@RestController
@RequestMapping(value = "/tasks")
@SecurityRequirement(name = "api_key")
public class TaskResource {

    private final TaskService taskService;

    @Inject
    public TaskResource(TaskService taskService) {
        this.taskService = taskService;
    }

    @Operation(summary = "Get the details of a task by its Id")
    @GetMapping(value = "/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(#taskId, 'TASK', T(com.yourcompany.app.enums.Permission).TASK_READ.name())")
    public com.netflix.conductor.common.run.Task getTask(@PathVariable("taskId") String taskId) {
        return taskService.getTask(taskId);
    }

    @Operation(summary = "Update a task")
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasPermission(#taskResult.taskId, 'TASK', T(com.yourcompany.app.enums.Permission).TASK_UPDATE.name())") // Update specific task
    public String updateTask(@RequestBody com.netflix.conductor.common.metadata.tasks.TaskResult taskResult) {
        return taskService.updateTask(taskResult);
    }

    @Operation(summary = "Batch update tasks", description = "Updates a list of tasks.  Should be used by the workers after the task execution is completed")
    @PostMapping(value = "/bulk", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    // This assumes that the bulk update would be done by a worker/manager role that has broader permissions.
    // If not, each task in the list would need to be individually checked.
    @PreAuthorize("hasPermission(null, 'TASK', T(com.yourcompany.app.enums.Permission).TASK_UPDATE.name())")
    public String updateTask(@RequestBody List<com.netflix.conductor.common.metadata.tasks.TaskResult> taskResults) {
        return taskService.updateTask(taskResults);
    }

    @Operation(summary = "Poll for a task of a specific type. Used by the workers to get next task for execution.")
    @GetMapping(value = "/poll/{taskType}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'TASK', T(com.yourcompany.app.enums.Permission).TASK_POLL.name())") // Poll general task type
    public com.netflix.conductor.common.run.Task poll(@PathVariable("taskType") String taskType,
                                                      @RequestParam(value = "workerid", required = false) String workerId,
                                                      @RequestParam(value = "domain", required = false) String domain) {
        return taskService.poll(taskType, workerId, domain);
    }

    @Operation(summary = "Poll for a batch of tasks of a specific type. Used by the workers to get next batch of tasks for execution.")
    @GetMapping(value = "/poll/batch/{taskType}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'TASK', T(com.yourcompany.app.enums.Permission).TASK_POLL.name())") // Poll general task type
    public List<com.netflix.conductor.common.run.Task> batchPoll(@PathVariable("taskType") String taskType,
                                                                 @RequestParam(value = "workerid", required = false) String workerId,
                                                                 @RequestParam(value = "domain", required = false) String domain,
                                                                 @RequestParam(value = "count", defaultValue = "1") int count,
                                                                 @RequestParam(value = "timeout", defaultValue = "100") int timeout) {
        return taskService.batchPoll(taskType, workerId, domain, count, timeout);
    }

    @Operation(summary = "Get the last poll data for a given task type")
    @GetMapping(value = "/poll/data/{taskType}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'TASK', T(com.yourcompany.app.enums.Permission).TASK_READ.name())")
    public List<PollData> getPollData(@PathVariable("taskType") String taskType) {
        return taskService.getPollData(taskType);
    }

    @Operation(summary = "Get the last poll data for all task types")
    @GetMapping(value = "/poll/data/all", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'TASK', T(com.yourcompany.app.enums.Permission).TASK_READ.name())")
    public List<PollData> getAllPollData() {
        return taskService.getAllPollData();
    }

    @Operation(summary = "Requeue a task")
    @PostMapping(value = "/requeue/{taskId}")
    @PreAuthorize("hasPermission(#taskId, 'TASK', T(com.yourcompany.app.enums.Permission).TASK_UPDATE.name())") // Requeue specific task
    public void requeueTask(@PathVariable("taskId") String taskId) {
        taskService.requeueTask(taskId);
    }

    @Operation(summary = "Get task execution logs")
    @GetMapping(value = "/log/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(#taskId, 'TASK', T(com.yourcompany.app.enums.Permission).TASK_LOG.name())") // Assuming TASK_LOG permission
    public List<TaskExecLog> getTaskLogs(@PathVariable("taskId") String taskId) {
        return taskService.getTaskLogs(taskId);
    }

    @Operation(summary = "Add task execution logs")
    @PostMapping(value = "/log", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasPermission(#taskExecLog.taskId, 'TASK', T(com.yourcompany.app.enums.Permission).TASK_LOG.name())") // Assuming TASK_LOG permission
    public void addTaskLog(@RequestBody TaskExecLog taskExecLog) {
        taskService.addTaskExecLog(taskExecLog);
    }

    @Operation(summary = "Search for tasks based on payload")
    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(null, 'TASK', T(com.yourcompany.app.enums.Permission).TASK_READ.name())")
    public List<TaskSummary> search(
            @RequestParam(value = "start", defaultValue = "0") int start,
            @RequestParam(value = "size", defaultValue = "100") int size,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "freeText", defaultValue = "*") String freeText,
            @RequestParam(value = "query", required = false) String query) {
        return taskService.search(start, size, sort, freeText, query);
    }

    @Operation(summary = "Remove a task from the system")
    @DeleteMapping(value = "/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasPermission(#taskId, 'TASK', T(com.yourcompany.app.enums.Permission).TASK_DELETE.name())") // Assuming TASK_DELETE permission
    public void removeTask(@PathVariable("taskId") String taskId) {
        taskService.removeTask(taskId);
    }
}