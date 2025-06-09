/*
 * Copyright 2022 Conductor Authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.core.execution;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.netflix.conductor.annotations.Trace;
import com.netflix.conductor.annotations.VisibleForTesting;
import com.netflix.conductor.common.metadata.tasks.*;
import com.netflix.conductor.common.metadata.workflow.RerunWorkflowRequest;
import com.netflix.conductor.common.metadata.workflow.SkipTaskRequest;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowTask;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.common.utils.TaskUtils;
import com.netflix.conductor.core.WorkflowContext;
import com.netflix.conductor.core.config.ConductorProperties;
import com.netflix.conductor.core.dal.ExecutionDAOFacade;
import com.netflix.conductor.core.exception.*;
import com.netflix.conductor.core.execution.tasks.SystemTaskRegistry;
import com.netflix.conductor.core.execution.tasks.Terminate;
import com.netflix.conductor.core.execution.tasks.WorkflowSystemTask;
import com.netflix.conductor.core.listener.TaskStatusListener;
import com.netflix.conductor.core.listener.WorkflowStatusListener;
import com.netflix.conductor.core.listener.WorkflowStatusListener.WorkflowEventType;
import com.netflix.conductor.core.metadata.MetadataMapperService;
import com.netflix.conductor.core.utils.IDGenerator;
import com.netflix.conductor.core.utils.ParametersUtils;
import com.netflix.conductor.core.utils.QueueUtils;
import com.netflix.conductor.core.utils.Utils;
import com.netflix.conductor.dao.MetadataDAO;
import com.netflix.conductor.dao.QueueDAO;
import com.netflix.conductor.metrics.Monitors;
import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;
import com.netflix.conductor.service.ExecutionLockService;

// Import your security classes
import com.yourcompany.conductor.security.AESUtil; // For direct decryption
import com.yourcompany.conductor.security.EncryptionKeyProvider; // For key
import com.yourcompany.conductor.security.SensitiveDataDetector; // For detecting sensitive fields
import com.yourcompany.conductor.security.WorkflowInputEncryptor; // Helper for encryption
import com.yourcompany.conductor.security.WorkflowPreProcessor; // Your existing preprocessor

import static com.netflix.conductor.core.utils.Utils.DECIDER_QUEUE;
import static com.netflix.conductor.model.TaskModel.Status.*;

/** Workflow services provider interface */
@Trace
@Component
@ConditionalOnProperty(name = "conductor.secrets.enabled", havingValue = "true")
public class WorkflowExecutorSecretOps implements WorkflowExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowExecutorSecretOps.class);
    private static final int EXPEDITED_PRIORITY = 10;
    private static final String CLASS_NAME = WorkflowExecutor.class.getSimpleName();
    private static final Predicate<TaskModel> UNSUCCESSFUL_TERMINAL_TASK =
            task -> !task.getStatus().isSuccessful() && task.getStatus().isTerminal();
    private static final Predicate<TaskModel> UNSUCCESSFUL_JOIN_TASK =
            UNSUCCESSFUL_TERMINAL_TASK.and(t -> TaskType.TASK_TYPE_JOIN.equals(t.getTaskType()));
    private static final Predicate<TaskModel> NON_TERMINAL_TASK =
            task -> !task.getStatus().isTerminal();
    private final MetadataDAO metadataDAO;
    private final QueueDAO queueDAO;
    private final DeciderService deciderService;
    private final ConductorProperties properties;
    private final MetadataMapperService metadataMapperService;
    private final ExecutionDAOFacade executionDAOFacade;
    private final ParametersUtils parametersUtils;
    private final IDGenerator idGenerator;
    private final WorkflowStatusListener workflowStatusListener;
    private final TaskStatusListener taskStatusListener;
    private final SystemTaskRegistry systemTaskRegistry;
    private long activeWorkerLastPollMs;
    private final ExecutionLockService executionLockService;
    private final WorkflowPreProcessor workflowPreProcessor; // Your existing preprocessor
    private final WorkflowInputEncryptor workflowInputEncryptor; // Helper for encryption

    private final Predicate<PollData> validateLastPolledTime =
            pollData ->
                    pollData.getLastPollTime()
                            > System.currentTimeMillis() - activeWorkerLastPollMs;

    public WorkflowExecutorSecretOps(
            DeciderService deciderService,
            MetadataDAO metadataDAO,
            QueueDAO queueDAO,
            MetadataMapperService metadataMapperService,
            WorkflowStatusListener workflowStatusListener,
            TaskStatusListener taskStatusListener,
            ExecutionDAOFacade executionDAOFacade,
            ConductorProperties properties,
            ExecutionLockService executionLockService,
            SystemTaskRegistry systemTaskRegistry,
            ParametersUtils parametersUtils,
            IDGenerator idGenerator,
            WorkflowPreProcessor workflowPreProcessor,
            WorkflowInputEncryptor workflowInputEncryptor) {
        this.deciderService = deciderService;
        this.metadataDAO = metadataDAO;
        this.queueDAO = queueDAO;
        this.properties = properties;
        this.metadataMapperService = metadataMapperService;
        this.executionDAOFacade = executionDAOFacade;
        this.activeWorkerLastPollMs = properties.getActiveWorkerLastPollTimeout().toMillis();
        this.workflowStatusListener = workflowStatusListener;
        this.taskStatusListener = taskStatusListener;
        this.executionLockService = executionLockService;
        this.parametersUtils = parametersUtils;
        this.idGenerator = idGenerator;
        this.systemTaskRegistry = systemTaskRegistry;
        this.workflowPreProcessor = workflowPreProcessor;
        this.workflowInputEncryptor = workflowInputEncryptor;
    }

    /**
     * @param workflowId the id of the workflow for which task callbacks are to be reset
     * @throws ConflictException if the workflow is in terminal state
     */
    @Override
    public void resetCallbacksForWorkflow(String workflowId) {
        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);
        if (workflow.getStatus().isTerminal()) {
            throw new ConflictException(
                    "Workflow is in terminal state. Status = %s", workflow.getStatus());
        }

        workflow.getTasks().stream()
                .filter(
                        task ->
                                !systemTaskRegistry.isSystemTask(task.getTaskType())
                                        && SCHEDULED == task.getStatus()
                                        && task.getCallbackAfterSeconds() > 0)
                .forEach(
                        task -> {
                            if (queueDAO.resetOffsetTime(
                                    QueueUtils.getQueueName(task), task.getTaskId())) {
                                task.setCallbackAfterSeconds(0);
                                executionDAOFacade.updateTask(task);
                            }
                        });
    }

    @Override
    public String rerun(RerunWorkflowRequest request) {
        Utils.checkNotNull(request.getReRunFromWorkflowId(), "reRunFromWorkflowId is missing");
        if (!rerunWF(
                request.getReRunFromWorkflowId(),
                request.getReRunFromTaskId(),
                request.getTaskInput(),
                request.getWorkflowInput(),
                request.getCorrelationId())) {
            throw new IllegalArgumentException(
                    "Task " + request.getReRunFromTaskId() + " not found");
        }
        return request.getReRunFromWorkflowId();
    }

    /**
     * @param workflowId the id of the workflow to be restarted
     * @param useLatestDefinitions if true, use the latest workflow and task definitions upon
     * restart
     * @throws ConflictException Workflow is not in a terminal state.
     * @throws NotFoundException Workflow definition is not found or Workflow is deemed
     * non-restartable as per workflow definition.
     */
    @Override
    public void restart(String workflowId, boolean useLatestDefinitions) {
        final WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);

        if (!workflow.getStatus().isTerminal()) {
            String errorMsg =
                    String.format(
                            "Workflow: %s is not in terminal state, unable to restart.", workflow);
            LOGGER.error(errorMsg);
            throw new ConflictException(errorMsg);
        }

        WorkflowDef workflowDef;
        if (useLatestDefinitions) {
            workflowDef =
                    metadataDAO
                            .getLatestWorkflowDef(workflow.getWorkflowName())
                            .orElseThrow(
                                    () ->
                                            new NotFoundException(
                                                    "Unable to find latest definition for %s",
                                                    workflowId));
            workflow.setWorkflowDefinition(workflowDef);
            workflowDef = metadataMapperService.populateTaskDefinitions(workflowDef);
        } else {
            workflowDef =
                    Optional.ofNullable(workflow.getWorkflowDefinition())
                            .orElseGet(
                                    () ->
                                            metadataDAO
                                                    .getWorkflowDef(
                                                            workflow.getWorkflowName(),
                                                            workflow.getWorkflowVersion())
                                                    .orElseThrow(
                                                            () ->
                                                                    new NotFoundException(
                                                                            "Unable to find definition for %s",
                                                                            workflowId)));
        }

        if (!workflowDef.isRestartable()
                && workflow.getStatus()
                .equals(
                        WorkflowModel.Status
                                .COMPLETED)) { // Can only restart non-completed workflows
            // when the configuration is set to false
            throw new NotFoundException("Workflow: %s is non-restartable", workflow);
        }

        executionDAOFacade.resetWorkflow(workflowId);

        workflow.getTasks().clear();
        workflow.setReasonForIncompletion(null);
        workflow.setFailedTaskId(null);
        workflow.setCreateTime(System.currentTimeMillis());
        workflow.setEndTime(0);
        workflow.setLastRetriedTime(0);
        workflow.setStatus(WorkflowModel.Status.RUNNING);
        workflow.setOutput(null);
        workflow.setExternalOutputPayloadStoragePath(null);

        try {
            executionDAOFacade.createWorkflow(workflow);
            notifyWorkflowStatusListener(workflow, WorkflowEventType.RESTARTED);
        } catch (Exception e) {
            Monitors.recordWorkflowStartError(
                    workflowDef.getName(), WorkflowContext.get().getClientApp());
            LOGGER.error("Unable to restart workflow: {}", workflowDef.getName(), e);
            terminateWorkflow(workflowId, "Error when restarting the workflow");
            throw e;
        }

        metadataMapperService.populateWorkflowWithDefinitions(workflow);
        decide(workflowId);

        updateAndPushParents(workflow, "restarted");
    }

    /**
     * Gets the last instance of each failed task and reschedule each Gets all cancelled tasks and
     * schedule all of them except JOIN (join should change status to INPROGRESS) Switch workflow
     * back to RUNNING status and call decider.
     *
     * @param workflowId the id of the workflow to be retried
     */
    @Override
    public void retry(String workflowId, boolean resumeSubworkflowTasks) {
        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);
        if (!workflow.getStatus().isTerminal()) {
            throw new NotFoundException(
                    "Workflow is still running.  status=%s", workflow.getStatus());
        }
        if (workflow.getTasks().isEmpty()) {
            throw new ConflictException("Workflow has not started yet");
        }

        if (resumeSubworkflowTasks) {
            Optional<TaskModel> taskToRetry =
                    workflow.getTasks().stream().filter(UNSUCCESSFUL_TERMINAL_TASK).findFirst();
            if (taskToRetry.isPresent()) {
                workflow = findLastFailedSubWorkflowIfAny(taskToRetry.get(), workflow);
                retry(workflow);
                updateAndPushParents(workflow, "retried");
            }
        } else {
            retry(workflow);
            updateAndPushParents(workflow, "retried");
        }
    }

    private void updateAndPushParents(WorkflowModel workflow, String operation) {
        String workflowIdentifier = "";
        while (workflow.hasParent()) {
            TaskModel subWorkflowTask =
                    executionDAOFacade.getTaskModel(workflow.getParentWorkflowTaskId());
            if (subWorkflowTask.getWorkflowTask().isOptional()) {
                LOGGER.info(
                        "Sub workflow task {} is optional, skip updating parents", subWorkflowTask);
                break;
            }
            subWorkflowTask.setSubworkflowChanged(true);
            subWorkflowTask.setStatus(IN_PROGRESS);
            executionDAOFacade.updateTask(subWorkflowTask);

            String currentWorkflowIdentifier = workflow.toShortString();
            workflowIdentifier =
                    !workflowIdentifier.equals("")
                            ? String.format(
                            "%s -> %s", currentWorkflowIdentifier, workflowIdentifier)
                            : currentWorkflowIdentifier;
            TaskExecLog log =
                    new TaskExecLog(
                            String.format("Sub workflow %s %s.", workflowIdentifier, operation));
            log.setTaskId(subWorkflowTask.getTaskId());
            executionDAOFacade.addTaskExecLog(Collections.singletonList(log));
            LOGGER.info("Task {} updated. {}", log.getTaskId(), log.getLog());

            String parentWorkflowId = workflow.getParentWorkflowId();
            WorkflowModel parentWorkflow =
                    executionDAOFacade.getWorkflowModel(parentWorkflowId, true);
            parentWorkflow.setStatus(WorkflowModel.Status.RUNNING);
            parentWorkflow.setLastRetriedTime(System.currentTimeMillis());
            executionDAOFacade.updateWorkflow(parentWorkflow);

            try {
                WorkflowEventType event = WorkflowEventType.valueOf(operation.toUpperCase());
                notifyWorkflowStatusListener(parentWorkflow, event);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Unknown workflow operation: {}", operation);
            }

            expediteLazyWorkflowEvaluation(parentWorkflowId);

            workflow = parentWorkflow;
        }
    }

    private void retry(WorkflowModel workflow) {
        Map<String, TaskModel> retriableMap = new HashMap<>();
        for (TaskModel task : workflow.getTasks()) {
            switch (task.getStatus()) {
                case FAILED:
                    if (task.getTaskType().equalsIgnoreCase(TaskType.JOIN.toString())
                            || task.getTaskType()
                            .equalsIgnoreCase(TaskType.EXCLUSIVE_JOIN.toString())) {
                        @SuppressWarnings("unchecked")
                        List<String> joinOn = (List<String>) task.getInputData().get("joinOn");
                        boolean joinOnFailedPermissive = isJoinOnFailedPermissive(joinOn, workflow);
                        if (joinOnFailedPermissive) {
                            task.setStatus(IN_PROGRESS);
                            addTaskToQueue(task);
                            break;
                        }
                    }
                case FAILED_WITH_TERMINAL_ERROR:
                case TIMED_OUT:
                    retriableMap.put(task.getReferenceTaskName(), task);
                    break;
                case CANCELED:
                    if (task.getTaskType().equalsIgnoreCase(TaskType.JOIN.toString())
                            || task.getTaskType().equalsIgnoreCase(TaskType.DO_WHILE.toString())) {
                        task.setStatus(IN_PROGRESS);
                        addTaskToQueue(task);
                    } else {
                        retriableMap.put(task.getReferenceTaskName(), task);
                    }
                    break;
                default:
                    retriableMap.remove(task.getReferenceTaskName());
                    break;
            }
        }

        if (retriableMap.values().size() == 0
                && workflow.getStatus() != WorkflowModel.Status.TIMED_OUT) {
            throw new ConflictException(
                    "There are no retryable tasks! Use restart if you want to attempt entire workflow execution again.");
        }

        workflow.setStatus(WorkflowModel.Status.RUNNING);
        workflow.setLastRetriedTime(System.currentTimeMillis());
        String lastReasonForIncompletion = workflow.getReasonForIncompletion();
        workflow.setReasonForIncompletion(null);
        queueDAO.push(
                DECIDER_QUEUE,
                workflow.getWorkflowId(),
                workflow.getPriority(),
                properties.getWorkflowOffsetTimeout().getSeconds());
        executionDAOFacade.updateWorkflow(workflow);
        notifyWorkflowStatusListener(workflow, WorkflowEventType.RETRIED);
        LOGGER.info(
                "Workflow {} that failed due to '{}' was retried",
                workflow.toShortString(),
                lastReasonForIncompletion);

        final WorkflowModel finalWorkflow = workflow;
        List<TaskModel> retriableTasks =
                retriableMap.values().stream()
                        .sorted(Comparator.comparingInt(TaskModel::getSeq))
                        .map(task -> taskToBeRescheduled(finalWorkflow, task))
                        .collect(Collectors.toList());

        dedupAndAddTasks(workflow, retriableTasks);
        executionDAOFacade.updateTasks(workflow.getTasks());
        scheduleTask(workflow, retriableTasks);
    }

    private WorkflowModel findLastFailedSubWorkflowIfAny(
            TaskModel task, WorkflowModel parentWorkflow) {
        if (TaskType.TASK_TYPE_SUB_WORKFLOW.equals(task.getTaskType())
                && UNSUCCESSFUL_TERMINAL_TASK.test(task)) {
            WorkflowModel subWorkflow =
                    executionDAOFacade.getWorkflowModel(task.getSubWorkflowId(), true);
            Optional<TaskModel> taskToRetry =
                    subWorkflow.getTasks().stream().filter(UNSUCCESSFUL_TERMINAL_TASK).findFirst();
            if (taskToRetry.isPresent()) {
                return findLastFailedSubWorkflowIfAny(taskToRetry.get(), subWorkflow);
            }
        }
        return parentWorkflow;
    }

    /**
     * Reschedule a task
     *
     * @param task failed or cancelled task
     * @return new instance of a task with "SCHEDULED" status
     */
    private TaskModel taskToBeRescheduled(WorkflowModel workflow, TaskModel task) {
        TaskModel taskToBeRetried = task.copy();
        taskToBeRetried.setTaskId(idGenerator.generate());
        taskToBeRetried.setRetriedTaskId(task.getTaskId());
        taskToBeRetried.setStatus(SCHEDULED);
        taskToBeRetried.setRetryCount(task.getRetryCount() + 1);
        taskToBeRetried.setRetried(false);
        taskToBeRetried.setPollCount(0);
        taskToBeRetried.setCallbackAfterSeconds(0);
        taskToBeRetried.setSubWorkflowId(null);
        taskToBeRetried.setScheduledTime(0);
        taskToBeRetried.setStartTime(0);
        taskToBeRetried.setEndTime(0);
        taskToBeRetried.setWorkerId(null);
        taskToBeRetried.setReasonForIncompletion(null);
        taskToBeRetried.setSeq(0);

        // perform parameter replacement for retried task
        // Note: parametersUtils.getTaskInput does NOT call any processors here, it performs its own variable substitution
        Map<String, Object> taskInput =
                parametersUtils.getTaskInput(
                        taskToBeRetried.getWorkflowTask().getInputParameters(),
                        workflow,
                        taskToBeRetried.getWorkflowTask().getTaskDefinition(),
                        taskToBeRetried.getTaskId());
        taskToBeRetried.getInputData().putAll(taskInput);

        task.setRetried(true);
        task.setExecuted(true);
        return taskToBeRetried;
    }

    private void endExecution(WorkflowModel workflow, TaskModel terminateTask) {
        boolean raiseFinalizedNotification = false;
        if (terminateTask != null) {
            String terminationStatus =
                    (String)
                            terminateTask
                                    .getInputData()
                                    .get(Terminate.getTerminationStatusParameter());
            String reason =
                    (String)
                            terminateTask
                                    .getInputData()
                                    .get(Terminate.getTerminationReasonParameter());
            if (StringUtils.isBlank(reason)) {
                reason =
                        String.format(
                                "Workflow is %s by TERMINATE task: %s",
                                terminationStatus, terminateTask.getTaskId());
            }
            if (WorkflowModel.Status.FAILED.name().equals(terminationStatus)) {
                workflow.setStatus(WorkflowModel.Status.FAILED);
                workflow =
                        terminate(
                                workflow,
                                new TerminateWorkflowException(
                                        reason, workflow.getStatus(), terminateTask));
            } else {
                workflow.setReasonForIncompletion(reason);
                workflow = completeWorkflow(workflow);
                raiseFinalizedNotification = true;
            }
        } else {
            workflow = completeWorkflow(workflow);
            raiseFinalizedNotification = true;
        }
        cancelNonTerminalTasks(workflow, raiseFinalizedNotification);
    }

    /**
     * @param workflow the workflow to be completed
     * @throws ConflictException if workflow is already in terminal state.
     */
    @VisibleForTesting
    WorkflowModel completeWorkflow(WorkflowModel workflow) {
        LOGGER.debug("Completing workflow execution for {}", workflow.getWorkflowId());

        if (workflow.getStatus().equals(WorkflowModel.Status.COMPLETED)) {
            queueDAO.remove(DECIDER_QUEUE, workflow.getWorkflowId());
            executionDAOFacade.removeFromPendingWorkflow(
                    workflow.getWorkflowName(), workflow.getWorkflowId());
            LOGGER.debug("Workflow: {} has already been completed.", workflow.getWorkflowId());
            return workflow;
        }

        if (workflow.getStatus().isTerminal()) {
            String msg =
                    "Workflow is already in terminal state. Current status: "
                            + workflow.getStatus();
            throw new ConflictException(msg);
        }

        deciderService.updateWorkflowOutput(workflow, null);

        workflow.setStatus(WorkflowModel.Status.COMPLETED);

        List<TaskModel> failedTasks =
                workflow.getTasks().stream()
                        .filter(
                                t ->
                                        FAILED.equals(t.getStatus())
                                                || FAILED_WITH_TERMINAL_ERROR.equals(t.getStatus()))
                        .collect(Collectors.toList());

        workflow.getFailedReferenceTaskNames()
                .addAll(
                        failedTasks.stream()
                                .map(TaskModel::getReferenceTaskName)
                                .collect(Collectors.toSet()));

        workflow.getFailedTaskNames()
                .addAll(
                        failedTasks.stream()
                                .map(TaskModel::getTaskDefName)
                                .collect(Collectors.toSet()));

        executionDAOFacade.updateWorkflow(workflow);
        LOGGER.debug("Completed workflow execution for {}", workflow.getWorkflowId());
        notifyWorkflowStatusListener(workflow, WorkflowEventType.COMPLETED);
        Monitors.recordWorkflowCompletion(
                workflow.getWorkflowName(),
                workflow.getEndTime() - workflow.getCreateTime(),
                workflow.getOwnerApp());

        if (workflow.hasParent()) {
            updateParentWorkflowTask(workflow);
            LOGGER.info(
                    "{} updated parent {} task {}",
                    workflow.toShortString(),
                    workflow.getParentWorkflowId(),
                    workflow.getParentWorkflowTaskId());
            expediteLazyWorkflowEvaluation(workflow.getParentWorkflowId());
        }

        executionLockService.releaseLock(workflow.getWorkflowId());
        executionLockService.deleteLock(workflow.getWorkflowId());
        return workflow;
    }

    @Override
    public void terminateWorkflow(String workflowId, String reason) {
        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);
        if (WorkflowModel.Status.COMPLETED.equals(workflow.getStatus())) {
            throw new ConflictException("Cannot terminate a COMPLETED workflow.");
        }
        workflow.setStatus(WorkflowModel.Status.TERMINATED);
        terminateWorkflow(workflow, reason, null);
    }

    /**
     * @param workflow the workflow to be terminated
     * @param reason the reason for termination
     * @param failureWorkflow the failure workflow (if any) to be triggered as a result of this
     * termination
     */
    @Override
    public WorkflowModel terminateWorkflow(
            WorkflowModel workflow, String reason, String failureWorkflow) {
        try {
            executionLockService.acquireLock(workflow.getWorkflowId(), 60000);

            if (!workflow.getStatus().isTerminal()) {
                workflow.setStatus(WorkflowModel.Status.TERMINATED);
            }

            try {
                deciderService.updateWorkflowOutput(workflow, null);
            } catch (Exception e) {
                LOGGER.error(
                        "Failed to update output data for workflow: {}",
                        workflow.getWorkflowId(),
                        e);
                Monitors.error(CLASS_NAME, "terminateWorkflow");
            }

            List<TaskModel> failedTasks =
                    workflow.getTasks().stream()
                            .filter(
                                    t ->
                                            FAILED.equals(t.getStatus())
                                                    || FAILED_WITH_TERMINAL_ERROR.equals(
                                                    t.getStatus()))
                            .collect(Collectors.toList());

            workflow.getFailedReferenceTaskNames()
                    .addAll(
                            failedTasks.stream()
                                    .map(TaskModel::getReferenceTaskName)
                                    .collect(Collectors.toSet()));

            workflow.getFailedTaskNames()
                    .addAll(
                            failedTasks.stream()
                                    .map(TaskModel::getTaskDefName)
                                    .collect(Collectors.toSet()));

            String workflowId = workflow.getWorkflowId();
            workflow.setReasonForIncompletion(reason);
            executionDAOFacade.updateWorkflow(workflow);
            notifyWorkflowStatusListener(workflow, WorkflowEventType.TERMINATED);
            Monitors.recordWorkflowTermination(
                    workflow.getWorkflowName(), workflow.getStatus(), workflow.getOwnerApp());
            LOGGER.info("Workflow {} is terminated because of {}", workflowId, reason);
            List<TaskModel> tasks = workflow.getTasks();
            try {
                tasks.forEach(
                        task -> queueDAO.remove(QueueUtils.getQueueName(task), task.getTaskId()));
            } catch (Exception e) {
                LOGGER.warn(
                        "Error removing task(s) from queue during workflow termination : {}",
                        workflowId,
                        e);
            }

            if (workflow.hasParent()) {
                updateParentWorkflowTask(workflow);
                LOGGER.info(
                        "{} updated parent {} task {}",
                        workflow.toShortString(),
                        workflow.getParentWorkflowId(),
                        workflow.getParentWorkflowTaskId());
                expediteLazyWorkflowEvaluation(workflow.getParentWorkflowId());
            }

            if (!StringUtils.isBlank(failureWorkflow)) {
                Map<String, Object> input = new HashMap<>(workflow.getInput());
                input.put("workflowId", workflowId);
                input.put("reason", reason);
                input.put("failureStatus", workflow.getStatus().toString());
                if (workflow.getFailedTaskId() != null) {
                    input.put("failureTaskId", workflow.getFailedTaskId());
                }
                input.put("failedWorkflow", workflow);

                try {
                    String failureWFId = idGenerator.generate();
                    StartWorkflowInput startWorkflowInput = new StartWorkflowInput();
                    startWorkflowInput.setName(failureWorkflow);
                    startWorkflowInput.setWorkflowInput(input);
                    startWorkflowInput.setCorrelationId(workflow.getCorrelationId());
                    startWorkflowInput.setTaskToDomain(workflow.getTaskToDomain());
                    startWorkflowInput.setWorkflowId(failureWFId);
                    startWorkflowInput.setTriggeringWorkflowId(workflowId);

                    startWorkflow(startWorkflowInput);

                    workflow.addOutput("conductor.failure_workflow", failureWFId);
                } catch (Exception e) {
                    LOGGER.error("Failed to start error workflow", e);
                    workflow.getOutput()
                            .put(
                                    "conductor.failure_workflow",
                                    "Error workflow "
                                            + failureWorkflow
                                            + " failed to start.  reason: "
                                            + e.getMessage());
                    Monitors.recordWorkflowStartError(
                            failureWorkflow, WorkflowContext.get().getClientApp());
                }
                executionDAOFacade.updateWorkflow(workflow);
            }
            executionDAOFacade.removeFromPendingWorkflow(
                    workflow.getWorkflowName(), workflow.getWorkflowId());

            List<String> erroredTasks = cancelNonTerminalTasks(workflow);
            if (!erroredTasks.isEmpty()) {
                throw new NonTransientException(
                        String.format(
                                "Error canceling system tasks: %s",
                                String.join(",", erroredTasks)));
            }
            return workflow;
        } finally {
            executionLockService.releaseLock(workflow.getWorkflowId());
            executionLockService.deleteLock(workflow.getWorkflowId());
        }
    }

    /**
     * @param taskResult the task result to be updated.
     * @throws IllegalArgumentException if the {@link TaskResult} is null. @Returns Updated task
     * @throws NotFoundException if the Task is not found.
     */
    @Override
    public TaskModel updateTask(TaskResult taskResult) {
        if (taskResult == null) {
            throw new IllegalArgumentException("Task object is null");
        } else if (taskResult.isExtendLease()) {
            extendLease(taskResult);
            return null;
        }

        String workflowId = taskResult.getWorkflowInstanceId();
        WorkflowModel workflowInstance = executionDAOFacade.getWorkflowModel(workflowId, false);

        TaskModel task =
                Optional.ofNullable(executionDAOFacade.getTaskModel(taskResult.getTaskId()))
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "No such task found by id: %s",
                                                taskResult.getTaskId()));

        LOGGER.debug("Task: {} belonging to Workflow {} being updated", task, workflowInstance);

        String taskQueueName = QueueUtils.getQueueName(task);

        if (task.getStatus().isTerminal()) {
            taskQueueName = QueueUtils.getQueueName(task); // Ensure queue name is current
            queueDAO.remove(taskQueueName, taskResult.getTaskId());
            LOGGER.info(
                    "Task: {} has already finished execution with status: {} within workflow: {}. Removed task from queue: {}",
                    task.getTaskId(),
                    task.getStatus(),
                    task.getWorkflowInstanceId(),
                    taskQueueName);
            Monitors.recordUpdateConflict(
                    task.getTaskType(), workflowInstance.getWorkflowName(), task.getStatus());
            return task;
        }

        if (workflowInstance.getStatus().isTerminal()) {
            taskQueueName = QueueUtils.getQueueName(task); // Ensure queue name is current
            queueDAO.remove(taskQueueName, taskResult.getTaskId());
            LOGGER.info(
                    "Workflow: {} has already finished execution. Task update for: {} ignored and removed from Queue: {}.",
                    workflowInstance,
                    taskResult.getTaskId(),
                    taskQueueName);
            Monitors.recordUpdateConflict(
                    task.getTaskType(),
                    workflowInstance.getWorkflowName(),
                    workflowInstance.getStatus());
            return task;
        }

        if (!systemTaskRegistry.isSystemTask(task.getTaskType())
                && taskResult.getStatus() == TaskResult.Status.IN_PROGRESS) {
            task.setStatus(SCHEDULED);
        } else {
            task.setStatus(TaskModel.Status.valueOf(taskResult.getStatus().name()));
        }
        task.setOutputMessage(taskResult.getOutputMessage());
        task.setReasonForIncompletion(taskResult.getReasonForIncompletion());
        task.setWorkerId(taskResult.getWorkerId());
        task.setCallbackAfterSeconds(taskResult.getCallbackAfterSeconds());

        // --- NEW LOGIC: ENCRYPT TASK OUTPUT BEFORE PERSISTENCE ---
        if (taskResult.getOutputData() != null && !taskResult.getOutputData().isEmpty()) {
            LOGGER.debug("Encrypting task output for task: {}", taskResult.getTaskId());
            try {
                Map<String, Object> encryptedOutput = workflowInputEncryptor.encryptMap(taskResult.getOutputData());
                task.setOutputData(encryptedOutput);
            } catch (Exception e) {
                LOGGER.error("ERROR: Failed to encrypt task output for task {}. Storing plaintext. Error: {}", taskResult.getTaskId(), e.getMessage(), e);
                // Decide: throw error or store plaintext? For sensitive data, throwing is usually safer.
                throw new RuntimeException("Failed to encrypt task output for task: " + taskResult.getTaskId(), e);
            }
        } else {
            task.setOutputData(taskResult.getOutputData()); // Set as is if no output or not encryptable
        }
        // --- END NEW LOGIC ---

        task.setSubWorkflowId(taskResult.getSubWorkflowId());

        if (StringUtils.isNotBlank(taskResult.getExternalOutputPayloadStoragePath())) {
            task.setExternalOutputPayloadStoragePath(
                    taskResult.getExternalOutputPayloadStoragePath());
        }

        if (task.getStatus().isTerminal()) {
            task.setEndTime(System.currentTimeMillis());
        }

        switch (task.getStatus()) {
            case COMPLETED:
            case CANCELED:
            case FAILED:
            case FAILED_WITH_TERMINAL_ERROR:
            case TIMED_OUT:
                try {
                    queueDAO.remove(taskQueueName, taskResult.getTaskId());
                    LOGGER.debug(
                            "Task: {} removed from taskQueue: {} since the task status is {}",
                            task,
                            taskQueueName,
                            task.getStatus().name());
                } catch (Exception e) {
                    String errorMsg =
                            String.format(
                                    "Error removing the message in queue for task: %s for workflow: %s",
                                    task.getTaskId(), workflowId);
                    LOGGER.warn(errorMsg, e);
                    Monitors.recordTaskQueueOpError(
                            task.getTaskType(), workflowInstance.getWorkflowName());
                }
                break;
            case IN_PROGRESS:
            case SCHEDULED:
                try {
                    long callBack = taskResult.getCallbackAfterSeconds();
                    queueDAO.postpone(
                            taskQueueName, task.getTaskId(), task.getWorkflowPriority(), callBack);
                    LOGGER.debug(
                            "Task: {} postponed in taskQueue: {} since the task status is {} with callbackAfterSeconds: {}",
                            task,
                            taskQueueName,
                            task.getStatus().name(),
                            callBack);
                } catch (Exception e) {
                    String errorMsg =
                            String.format(
                                    "Error postponing the message in queue for task: %s for workflow: %s",
                                    task.getTaskId(), workflowId);
                    LOGGER.error(errorMsg, e);
                    Monitors.recordTaskQueueOpError(
                            task.getTaskType(), workflowInstance.getWorkflowName());
                    throw new TransientException(errorMsg, e);
                }
                break;
            default:
                break;
        }

        try {
            executionDAOFacade.updateTask(task);
        } catch (Exception e) {
            String errorMsg =
                    String.format(
                            "Error updating task: %s for workflow: %s",
                            task.getTaskId(), workflowId);
            LOGGER.error(errorMsg, e);
            Monitors.recordTaskUpdateError(task.getTaskType(), workflowInstance.getWorkflowName());
            throw new TransientException(errorMsg, e);
        }

        try {
            notifyTaskStatusListener(task);
        } catch (Exception e) {
            String errorMsg =
                    String.format(
                            "Error while notifying TaskStatusListener: %s for workflow: %s",
                            task.getTaskId(), workflowId);
            LOGGER.error(errorMsg, e);
        }

        taskResult.getLogs().forEach(taskExecLog -> taskExecLog.setTaskId(task.getTaskId()));
        executionDAOFacade.addTaskExecLog(taskResult.getLogs());

        if (task.getStatus().isTerminal()) {
            long duration = getTaskDuration(0, task);
            long lastDuration = task.getEndTime() - task.getStartTime();
            Monitors.recordTaskExecutionTime(
                    task.getTaskDefName(), duration, true, task.getStatus());
            Monitors.recordTaskExecutionTime(
                    task.getTaskDefName(), lastDuration, false, task.getStatus());
        }

        if (!isLazyEvaluateWorkflow(workflowInstance.getWorkflowDefinition(), task)) {
            decide(workflowId);
        }
        return task;
    }

    private void notifyTaskStatusListener(TaskModel task) {
        switch (task.getStatus()) {
            case COMPLETED:
                taskStatusListener.onTaskCompleted(task);
                break;
            case CANCELED:
                taskStatusListener.onTaskCanceled(task);
                break;
            case FAILED:
                taskStatusListener.onTaskFailed(task);
                break;
            case FAILED_WITH_TERMINAL_ERROR:
                taskStatusListener.onTaskFailedWithTerminalError(task);
                break;
            case TIMED_OUT:
                taskStatusListener.onTaskTimedOut(task);
                break;
            case IN_PROGRESS:
                taskStatusListener.onTaskInProgress(task);
                break;
            case SCHEDULED:
            default:
                break;
        }
    }

    private void extendLease(TaskResult taskResult) {
        TaskModel task =
                Optional.ofNullable(executionDAOFacade.getTaskModel(taskResult.getTaskId()))
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "No such task found by id: %s",
                                                taskResult.getTaskId()));

        LOGGER.debug(
                "Extend lease for Task: {} belonging to Workflow: {}",
                task,
                task.getWorkflowInstanceId());
        if (!task.getStatus().isTerminal()) {
            try {
                executionDAOFacade.extendLease(task);
            } catch (Exception e) {
                String errorMsg =
                        String.format(
                                "Error extend lease for Task: %s belonging to Workflow: %s",
                                task.getTaskId(), task.getWorkflowInstanceId());
                LOGGER.error(errorMsg, e);
                Monitors.recordTaskExtendLeaseError(task.getTaskType(), task.getWorkflowType());
                throw new TransientException(errorMsg, e);
            }
        }
    }

    /**
     * Determines if a workflow can be lazily evaluated, if it meets any of these criteria
     *
     * <ul>
     * <li>The task is NOT a loop task within DO_WHILE
     * <li>The task is one of the intermediate tasks in a branch within a FORK_JOIN
     * <li>The task is forked from a FORK_JOIN_DYNAMIC
     * </ul>
     *
     * @param workflowDef The workflow definition of the workflow for which evaluation decision is
     * to be made
     * @param task The task which is attempting to trigger the evaluation
     * @return true if workflow can be lazily evaluated, false otherwise
     */
    @VisibleForTesting
    boolean isLazyEvaluateWorkflow(WorkflowDef workflowDef, TaskModel task) {
        if (task.isLoopOverTask()) {
            return false;
        }

        String taskRefName = task.getReferenceTaskName();
        List<WorkflowTask> workflowTasks = workflowDef.collectTasks();

        List<WorkflowTask> forkTasks =
                workflowTasks.stream()
                        .filter(t -> t.getType().equals(TaskType.FORK_JOIN.name()))
                        .collect(Collectors.toList());

        List<WorkflowTask> joinTasks =
                workflowTasks.stream()
                        .filter(t -> t.getType().equals(TaskType.JOIN.name()))
                        .collect(Collectors.toList());

        if (forkTasks.stream().anyMatch(fork -> fork.has(taskRefName))) {
            return joinTasks.stream().anyMatch(join -> join.getJoinOn().contains(taskRefName))
                    && task.getStatus().isSuccessful();
        }

        return workflowTasks.stream().noneMatch(t -> t.getTaskReferenceName().equals(taskRefName))
                && task.getStatus().isSuccessful();
    }

    @Override
    public TaskModel getTask(String taskId) {
        return Optional.ofNullable(executionDAOFacade.getTaskModel(taskId))
                .map(
                        task -> {
                            if (task.getWorkflowTask() != null) {
                                // Decrypt input data before returning task via API
                                try {
                                    task.setInputData(decryptTaskInputData(task.getInputData()));
                                } catch (Exception e) {
                                    LOGGER.error("ERROR: Failed to decrypt task input data for task {}. Returning encrypted data. Error: {}", task.getTaskId(), e.getMessage(), e);
                                    // Decide: return encrypted data or throw? For API, sometimes returning encrypted is acceptable for error visibility.
                                    // Throwing here might break UI/polling logic if not handled.
                                }
                                return metadataMapperService.populateTaskWithDefinition(task);
                            }
                            return task;
                        })
                .orElse(null);
    }

    @Override
    public List<Workflow> getRunningWorkflows(String workflowName, int version) {
        return executionDAOFacade.getPendingWorkflowsByName(workflowName, version);
    }

    @Override
    public List<String> getWorkflows(String name, Integer version, Long startTime, Long endTime) {
        return executionDAOFacade.getWorkflowsByName(name, startTime, endTime).stream()
                .filter(workflow -> workflow.getWorkflowVersion() == version)
                .map(Workflow::getWorkflowId)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getRunningWorkflowIds(String workflowName, int version) {
        return executionDAOFacade.getRunningWorkflowIds(workflowName, version);
    }

    /** Records a metric for the "decide" process. */
    @Override
    public WorkflowModel decide(String workflowId) {
        StopWatch watch = new StopWatch();
        watch.start();
        if (!executionLockService.acquireLock(workflowId, properties.getLockLeaseTime().toMillis())) {
            return null;
        }
        try {

            WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);
            if (workflow == null) {
                return null;
            }
            return decide(workflow);

        } finally {
            executionLockService.releaseLock(workflowId);
            watch.stop();
            Monitors.recordWorkflowDecisionTime(watch.getTime());
        }
    }

    /**
     * @param workflow the workflow to evaluate the state for
     * @return true if the workflow has completed (success or failed), false otherwise. Note: This
     * method does not acquire the lock on the workflow and should ony be called / overridden if
     * No locking is required or lock is acquired externally
     */
    private WorkflowModel decide(WorkflowModel workflow) {
        if (workflow.getStatus().isTerminal()) {
            if (!workflow.getStatus().isSuccessful()) {
                cancelNonTerminalTasks(workflow);
            }
            return workflow;
        }

        adjustStateIfSubWorkflowChanged(workflow);

        try {
            DeciderService.DeciderOutcome outcome = deciderService.decide(workflow);
            if (outcome.isComplete) {
                endExecution(workflow, outcome.terminateTask);
                return workflow;
            }

            List<TaskModel> tasksToBeScheduled = outcome.tasksToBeScheduled;
            setTaskDomains(tasksToBeScheduled, workflow);
            List<TaskModel> tasksToBeUpdated = outcome.tasksToBeUpdated;

            tasksToBeScheduled = dedupAndAddTasks(workflow, tasksToBeScheduled);

            boolean stateChanged = scheduleTask(workflow, tasksToBeScheduled);

            for (TaskModel task : outcome.tasksToBeScheduled) {
                executionDAOFacade.populateTaskData(task);

                // --- NEW LOGIC: DECRYPT TASK INPUT FOR SYSTEM TASKS BEFORE EXECUTION ---
                try {
                    task.setInputData(decryptTaskInputData(task.getInputData()));
                } catch (Exception e) {
                    LOGGER.error("ERROR: Failed to decrypt input for system task {}. Error: {}", task.getTaskId(), e.getMessage(), e);
                    throw new RuntimeException("Failed to decrypt input for system task: " + task.getTaskId(), e);
                }
                // --- END NEW LOGIC ---

                if (systemTaskRegistry.isSystemTask(task.getTaskType())
                        && NON_TERMINAL_TASK.test(task)) {
                    WorkflowSystemTask workflowSystemTask =
                            systemTaskRegistry.get(task.getTaskType());
                    if (!workflowSystemTask.isAsync()
                            && workflowSystemTask.execute(workflow, task, this)) {
                        tasksToBeUpdated.add(task);
                        stateChanged = true;
                    }
                }
            }

            if (!outcome.tasksToBeUpdated.isEmpty() || !tasksToBeScheduled.isEmpty()) {
                executionDAOFacade.updateTasks(tasksToBeUpdated);
            }

            if (stateChanged) {
                return decide(workflow);
            }

            if (!outcome.tasksToBeUpdated.isEmpty() || !tasksToBeScheduled.isEmpty()) {
                executionDAOFacade.updateWorkflow(workflow);
            }

            return workflow;

        } catch (TerminateWorkflowException twe) {
            LOGGER.info("Execution terminated of workflow: {}", workflow, twe);
            terminate(workflow, twe);
            return workflow;
        } catch (RuntimeException e) {
            LOGGER.error("Error deciding workflow: {}", workflow.getWorkflowId(), e);
            throw e;
        }
    }

    // --- NEW HELPER METHOD FOR DECRYPTION ---
    @SuppressWarnings("unchecked")
    private Map<String, Object> decryptTaskInputData(Map<String, Object> encryptedInputMap) {
        if (encryptedInputMap == null || encryptedInputMap.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, Object> decryptedMap = new HashMap<>();
        encryptedInputMap.forEach((key, value) -> {
            decryptedMap.put(key, deepDecryptValue(key, value));
        });
        LOGGER.debug("Decrypted task input map processed.");
        return decryptedMap;
    }

    @SuppressWarnings("unchecked")
    private Object deepDecryptValue(String key, Object value) {
        if (SensitiveDataDetector.isSensitive(key, value)) {
            try {
                String encryptedValue = SensitiveDataDetector.extractValue(value);
                String decryptedValue = AESUtil.decrypt(encryptedValue, EncryptionKeyProvider.getDefaultEncryptionKey());
                return decryptedValue; // Return plaintext string directly to the task
            } catch (Exception e) {
                LOGGER.error("ERROR: Failed to decrypt sensitive field '{}'. Returning encrypted value. Error: {}", key, e.getMessage(), e);
                // Decide: throw error or return encrypted value? For tasks, returning encrypted is often better than failing.
                return value; // Return original encrypted value if decryption fails
            }
        } else if (value instanceof Map) {
            Map<String, Object> nestedMap = (Map<String, Object>) value;
            Map<String, Object> processedNestedMap = new HashMap<>();
            nestedMap.forEach((nestedKey, nestedValue) -> {
                processedNestedMap.put(nestedKey, deepDecryptValue(nestedKey, nestedValue));
            });
            return processedNestedMap;
        } else if (value instanceof List) {
            List<Object> originalList = (List<Object>) value;
            return originalList.stream()
                    .map(item -> deepDecryptValue(key, item))
                    .collect(Collectors.toList());
        }
        return value; // Return as is for String, numbers, booleans etc.
    }
    // --- END NEW HELPER METHOD FOR DECRYPTION ---


    private void adjustStateIfSubWorkflowChanged(WorkflowModel workflow) {
        Optional<TaskModel> changedSubWorkflowTask = findChangedSubWorkflowTask(workflow);
        if (changedSubWorkflowTask.isPresent()) {
            TaskModel subWorkflowTask = changedSubWorkflowTask.get();
            subWorkflowTask.setSubworkflowChanged(false);
            executionDAOFacade.updateTask(subWorkflowTask);

            LOGGER.info(
                    "{} reset subworkflowChanged flag for {}",
                    workflow.toShortString(),
                    subWorkflowTask.getTaskId());

            if (workflow.getWorkflowDefinition().containsType(TaskType.TASK_TYPE_JOIN)
                    || workflow.getWorkflowDefinition()
                    .containsType(TaskType.TASK_TYPE_FORK_JOIN_DYNAMIC)) {
                workflow.getTasks().stream()
                        .filter(UNSUCCESSFUL_JOIN_TASK)
                        .peek(
                                task -> {
                                    task.setStatus(TaskModel.Status.IN_PROGRESS);
                                    addTaskToQueue(task);
                                })
                        .forEach(executionDAOFacade::updateTask);
            }
        }
    }

    private Optional<TaskModel> findChangedSubWorkflowTask(WorkflowModel workflow) {
        WorkflowDef workflowDef =
                Optional.ofNullable(workflow.getWorkflowDefinition())
                        .orElseGet(
                                () ->
                                        metadataDAO
                                                .getWorkflowDef(
                                                        workflow.getWorkflowName(),
                                                        workflow.getWorkflowVersion())
                                                .orElseThrow(
                                                        () ->
                                                                new TransientException(
                                                                        "Workflow Definition is not found")));
        if (workflowDef.containsType(TaskType.TASK_TYPE_SUB_WORKFLOW)
                || workflow.getWorkflowDefinition()
                .containsType(TaskType.TASK_TYPE_FORK_JOIN_DYNAMIC)) {
            return workflow.getTasks().stream()
                    .filter(
                            t ->
                                    t.getTaskType().equals(TaskType.TASK_TYPE_SUB_WORKFLOW)
                                            && t.isSubworkflowChanged()
                                            && !t.isRetried())
                    .findFirst();
        }
        return Optional.empty();
    }

    @VisibleForTesting
    List<String> cancelNonTerminalTasks(WorkflowModel workflow) {
        return cancelNonTerminalTasks(workflow, true);
    }

    List<String> cancelNonTerminalTasks(WorkflowModel workflow, boolean raiseFinalized) {
        List<String> erroredTasks = new ArrayList<>();
        for (TaskModel task : workflow.getTasks()) {
            if (!task.getStatus().isTerminal()) {
                task.setStatus(CANCELED);
                try {
                    notifyTaskStatusListener(task);
                } catch (Exception e) {
                    String errorMsg =
                            String.format(
                                    "Error while notifying TaskStatusListener: %s for workflow: %s",
                                    task.getTaskId(), task.getWorkflowInstanceId());
                    LOGGER.error(errorMsg, e);
                }
                if (systemTaskRegistry.isSystemTask(task.getTaskType())) {
                    WorkflowSystemTask workflowSystemTask =
                            systemTaskRegistry.get(task.getTaskType());
                    try {
                        workflowSystemTask.cancel(workflow, task, this);
                    } catch (Exception e) {
                        erroredTasks.add(task.getReferenceTaskName());
                        LOGGER.error(
                                "Error canceling system task:{}/{} in workflow: {}",
                                workflowSystemTask.getTaskType(),
                                task.getTaskId(),
                                workflow.getWorkflowId(),
                                e);
                    }
                }
                executionDAOFacade.updateTask(task);
            }
        }
        if (erroredTasks.isEmpty()) {
            try {
                if (raiseFinalized) {
                    notifyWorkflowStatusListener(workflow, WorkflowEventType.FINALIZED);
                }
                queueDAO.remove(DECIDER_QUEUE, workflow.getWorkflowId());
            } catch (Exception e) {
                LOGGER.error(
                        "Error removing workflow: {} from decider queue",
                        workflow.getWorkflowId(),
                        e);
            }
        }
        return erroredTasks;
    }

    @VisibleForTesting
    List<TaskModel> dedupAndAddTasks(WorkflowModel workflow, List<TaskModel> tasks) {
        Set<String> tasksInWorkflow =
                workflow.getTasks().stream()
                        .map(task -> task.getReferenceTaskName() + "_" + task.getRetryCount())
                        .collect(Collectors.toSet());

        List<TaskModel> dedupedTasks =
                tasks.stream()
                        .filter(
                                task ->
                                        !tasksInWorkflow.contains(
                                                task.getReferenceTaskName()
                                                        + "_"
                                                        + task.getRetryCount()))
                        .collect(Collectors.toList());

        workflow.getTasks().addAll(dedupedTasks);
        return dedupedTasks;
    }

    /**
     * @throws ConflictException if the workflow is in terminal state.
     */
    @Override
    public void pauseWorkflow(String workflowId) {
        try {
            executionLockService.acquireLock(workflowId, properties.getLockLeaseTime().toMillis());
            WorkflowModel.Status status = WorkflowModel.Status.PAUSED;
            WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, false);
            if (workflow.getStatus().isTerminal()) {
                throw new ConflictException(
                        "Workflow %s has ended, status cannot be updated.",
                        workflow.toShortString());
            }
            if (workflow.getStatus().equals(status)) {
                return;
            }
            workflow.setStatus(status);
            executionDAOFacade.updateWorkflow(workflow);

            notifyWorkflowStatusListener(workflow, WorkflowEventType.PAUSED);
        } finally {
            executionLockService.releaseLock(workflowId);
        }

        try {
            queueDAO.remove(DECIDER_QUEUE, workflowId);
        } catch (Exception e) {
            LOGGER.info(
                    "[pauseWorkflow] Error removing workflow: {} from decider queue",
                    workflowId,
                    e);
        }
    }

    /**
     * @param workflowId the workflow to be resumed
     * @throws IllegalStateException if the workflow is not in PAUSED state
     */
    @Override
    public void resumeWorkflow(String workflowId) {
        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, false);
        if (!workflow.getStatus().equals(WorkflowModel.Status.PAUSED)) {
            throw new IllegalStateException(
                    "The workflow "
                            + workflowId
                            + " is not PAUSED so cannot resume. "
                            + "Current status is "
                            + workflow.getStatus().name());
        }
        workflow.setStatus(WorkflowModel.Status.RUNNING);
        workflow.setLastRetriedTime(System.currentTimeMillis());
        queueDAO.push(
                DECIDER_QUEUE,
                workflow.getWorkflowId(),
                workflow.getPriority(),
                properties.getWorkflowOffsetTimeout().getSeconds());
        executionDAOFacade.updateWorkflow(workflow);
        notifyWorkflowStatusListener(workflow, WorkflowEventType.RESUMED);
        decide(workflowId);
    }

    /**
     * @param workflowId the id of the workflow
     * @param taskReferenceName the referenceName of the task to be skipped
     * @param skipTaskRequest the {@link SkipTaskRequest} object
     * @throws IllegalStateException
     */
    @Override
    public void skipTaskFromWorkflow(
            String workflowId, String taskReferenceName, SkipTaskRequest skipTaskRequest) {

        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);

        if (!workflow.getStatus().equals(WorkflowModel.Status.RUNNING)) {
            String errorMsg =
                    String.format(
                            "The workflow %s is not running so the task referenced by %s cannot be skipped",
                            workflowId, taskReferenceName);
            throw new IllegalStateException(errorMsg);
        }

        WorkflowTask workflowTask =
                workflow.getWorkflowDefinition().getTaskByRefName(taskReferenceName);
        if (workflowTask == null) {
            String errorMsg =
                    String.format(
                            "The task referenced by %s does not exist in the WorkflowDefinition %s",
                            taskReferenceName, workflow.getWorkflowName());
            throw new IllegalStateException(errorMsg);
        }

        workflow.getTasks()
                .forEach(
                        task -> {
                            if (task.getReferenceTaskName().equals(taskReferenceName)) {
                                String errorMsg =
                                        String.format(
                                                "The task referenced %s has already been processed, cannot be skipped",
                                                taskReferenceName);
                                throw new IllegalStateException(errorMsg);
                            }
                        });

        TaskModel taskToBeSkipped = new TaskModel();
        taskToBeSkipped.setTaskId(idGenerator.generate());
        taskToBeSkipped.setReferenceTaskName(taskReferenceName);
        taskToBeSkipped.setWorkflowInstanceId(workflowId);
        taskToBeSkipped.setWorkflowPriority(workflow.getPriority());
        taskToBeSkipped.setStatus(SKIPPED);
        taskToBeSkipped.setEndTime(System.currentTimeMillis());
        taskToBeSkipped.setTaskType(workflowTask.getName());
        taskToBeSkipped.setCorrelationId(workflow.getCorrelationId());
        if (skipTaskRequest != null) {
            // Note: If skipTaskRequest.getTaskInput/Output can contain sensitive data,
            // these would need encryption too. This is a potential future enhancement
            // if skipTask is a frequent path for sensitive data.
            taskToBeSkipped.setInputData(skipTaskRequest.getTaskInput());
            taskToBeSkipped.setOutputData(skipTaskRequest.getTaskOutput());
            taskToBeSkipped.setInputMessage(skipTaskRequest.getTaskInputMessage());
            taskToBeSkipped.setOutputMessage(skipTaskRequest.getTaskOutputMessage());
        }
        executionDAOFacade.createTasks(Collections.singletonList(taskToBeSkipped));
        decide(workflow.getWorkflowId());
    }

    @Override
    public TaskModel getTask(String taskId) {
        return Optional.ofNullable(executionDAOFacade.getTaskModel(taskId))
                .map(
                        task -> {
                            if (task.getWorkflowTask() != null) {
                                // --- NEW LOGIC: DECRYPT TASK INPUT DATA WHEN RETRIEVED VIA API ---
                                try {
                                    task.setInputData(decryptTaskInputData(task.getInputData()));
                                } catch (Exception e) {
                                    LOGGER.error("ERROR: Failed to decrypt task input data for task {}. Returning encrypted data. Error: {}", task.getTaskId(), e.getMessage(), e);
                                    // For API calls, often better to return data even if encrypted
                                }
                                // --- NEW LOGIC: DECRYPT TASK OUTPUT DATA WHEN RETRIEVED VIA API ---
                                try {
                                    task.setOutputData(decryptTaskInputData(task.getOutputData())); // Reusing decryptMap method for output map
                                } catch (Exception e) {
                                    LOGGER.error("ERROR: Failed to decrypt task output data for task {}. Returning encrypted data. Error: {}", task.getTaskId(), e.getMessage(), e);
                                }
                                // --- END NEW LOGIC ---
                                return metadataMapperService.populateTaskWithDefinition(task);
                            }
                            return task;
                        })
                .orElse(null);
    }

    @Override
    public List<Workflow> getRunningWorkflows(String workflowName, int version) {
        return executionDAOFacade.getPendingWorkflowsByName(workflowName, version);
    }

    @Override
    public List<String> getWorkflows(String name, Integer version, Long startTime, Long endTime) {
        return executionDAOFacade.getWorkflowsByName(name, startTime, endTime).stream()
                .filter(workflow -> workflow.getWorkflowVersion() == version)
                .map(Workflow::getWorkflowId)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getRunningWorkflowIds(String workflowName, int version) {
        return executionDAOFacade.getRunningWorkflowIds(workflowName, version);
    }

    /** Records a metric for the "decide" process. */
    @Override
    public WorkflowModel decide(String workflowId) {
        StopWatch watch = new StopWatch();
        watch.start();
        if (!executionLockService.acquireLock(workflowId, properties.getLockLeaseTime().toMillis())) {
            return null;
        }
        try {

            WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);
            if (workflow == null) {
                return null;
            }
            return decide(workflow);

        } finally {
            executionLockService.releaseLock(workflowId);
            watch.stop();
            Monitors.recordWorkflowDecisionTime(watch.getTime());
        }
    }

    /**
     * @param workflow the workflow to evaluate the state for
     * @return true if the workflow has completed (success or failed), false otherwise. Note: This
     * method does not acquire the lock on the workflow and should ony be called / overridden if
     * No locking is required or lock is acquired externally
     */
    private WorkflowModel decide(WorkflowModel workflow) {
        if (workflow.getStatus().isTerminal()) {
            if (!workflow.getStatus().isSuccessful()) {
                cancelNonTerminalTasks(workflow);
            }
            return workflow;
        }

        adjustStateIfSubWorkflowChanged(workflow);

        try {
            DeciderService.DeciderOutcome outcome = deciderService.decide(workflow);
            if (outcome.isComplete) {
                endExecution(workflow, outcome.terminateTask);
                return workflow;
            }

            List<TaskModel> tasksToBeScheduled = outcome.tasksToBeScheduled;
            setTaskDomains(tasksToBeScheduled, workflow);
            List<TaskModel> tasksToBeUpdated = outcome.tasksToBeUpdated;

            tasksToBeScheduled = dedupAndAddTasks(workflow, tasksToBeScheduled);

            boolean stateChanged = scheduleTask(workflow, tasksToBeScheduled);

            for (TaskModel task : outcome.tasksToBeScheduled) {
                executionDAOFacade.populateTaskData(task);

                // --- NEW LOGIC: DECRYPT TASK INPUT FOR SYSTEM TASKS BEFORE EXECUTION ---
                try {
                    task.setInputData(decryptTaskInputData(task.getInputData()));
                } catch (Exception e) {
                    LOGGER.error("ERROR: Failed to decrypt input for system task {}. Error: {}", task.getTaskId(), e.getMessage(), e);
                    throw new RuntimeException("Failed to decrypt input for system task: " + task.getTaskId(), e);
                }
                // --- END NEW LOGIC ---

                if (systemTaskRegistry.isSystemTask(task.getTaskType())
                        && NON_TERMINAL_TASK.test(task)) {
                    WorkflowSystemTask workflowSystemTask =
                            systemTaskRegistry.get(task.getTaskType());
                    if (!workflowSystemTask.isAsync()
                            && workflowSystemTask.execute(workflow, task, this)) {
                        tasksToBeUpdated.add(task);
                        stateChanged = true;
                    }
                }
            }

            if (!outcome.tasksToBeUpdated.isEmpty() || !tasksToBeScheduled.isEmpty()) {
                executionDAOFacade.updateTasks(tasksToBeUpdated);
            }

            if (stateChanged) {
                return decide(workflow);
            }

            if (!outcome.tasksToBeUpdated.isEmpty() || !tasksToBeScheduled.isEmpty()) {
                executionDAOFacade.updateWorkflow(workflow);
            }

            return workflow;

        } catch (TerminateWorkflowException twe) {
            LOGGER.info("Execution terminated of workflow: {}", workflow, twe);
            terminate(workflow, twe);
            return workflow;
        } catch (RuntimeException e) {
            LOGGER.error("Error deciding workflow: {}", workflow.getWorkflowId(), e);
            throw e;
        }
    }


    private void adjustStateIfSubWorkflowChanged(WorkflowModel workflow) {
        Optional<TaskModel> changedSubWorkflowTask = findChangedSubWorkflowTask(workflow);
        if (changedSubWorkflowTask.isPresent()) {
            TaskModel subWorkflowTask = changedSubWorkflowTask.get();
            subWorkflowTask.setSubworkflowChanged(false);
            executionDAOFacade.updateTask(subWorkflowTask);

            LOGGER.info(
                    "{} reset subworkflowChanged flag for {}",
                    workflow.toShortString(),
                    subWorkflowTask.getTaskId());

            if (workflow.getWorkflowDefinition().containsType(TaskType.TASK_TYPE_JOIN)
                    || workflow.getWorkflowDefinition()
                    .containsType(TaskType.TASK_TYPE_FORK_JOIN_DYNAMIC)) {
                workflow.getTasks().stream()
                        .filter(UNSUCCESSFUL_JOIN_TASK)
                        .peek(
                                task -> {
                                    task.setStatus(TaskModel.Status.IN_PROGRESS);
                                    addTaskToQueue(task);
                                })
                        .forEach(executionDAOFacade::updateTask);
            }
        }
    }

    private Optional<TaskModel> findChangedSubWorkflowTask(WorkflowModel workflow) {
        WorkflowDef workflowDef =
                Optional.ofNullable(workflow.getWorkflowDefinition())
                        .orElseGet(
                                () ->
                                        metadataDAO
                                                .getWorkflowDef(
                                                        workflow.getWorkflowName(),
                                                        workflow.getWorkflowVersion())
                                                .orElseThrow(
                                                        () ->
                                                                new TransientException(
                                                                        "Workflow Definition is not found")));
        if (workflowDef.containsType(TaskType.TASK_TYPE_SUB_WORKFLOW)
                || workflow.getWorkflowDefinition()
                .containsType(TaskType.TASK_TYPE_FORK_JOIN_DYNAMIC)) {
            return workflow.getTasks().stream()
                    .filter(
                            t ->
                                    t.getTaskType().equals(TaskType.TASK_TYPE_SUB_WORKFLOW)
                                            && t.isSubworkflowChanged()
                                            && !t.isRetried())
                    .findFirst();
        }
        return Optional.empty();
    }

    @VisibleForTesting
    List<String> cancelNonTerminalTasks(WorkflowModel workflow) {
        return cancelNonTerminalTasks(workflow, true);
    }

    List<String> cancelNonTerminalTasks(WorkflowModel workflow, boolean raiseFinalized) {
        List<String> erroredTasks = new ArrayList<>();
        for (TaskModel task : workflow.getTasks()) {
            if (!task.getStatus().isTerminal()) {
                task.setStatus(CANCELED);
                try {
                    notifyTaskStatusListener(task);
                } catch (Exception e) {
                    String errorMsg =
                            String.format(
                                    "Error while notifying TaskStatusListener: %s for workflow: %s",
                                    task.getTaskId(), task.getWorkflowInstanceId());
                    LOGGER.error(errorMsg, e);
                }
                if (systemTaskRegistry.isSystemTask(task.getTaskType())) {
                    WorkflowSystemTask workflowSystemTask =
                            systemTaskRegistry.get(task.getTaskType());
                    try {
                        workflowSystemTask.cancel(workflow, task, this);
                    } catch (Exception e) {
                        erroredTasks.add(task.getReferenceTaskName());
                        LOGGER.error(
                                "Error canceling system task:{}/{} in workflow: {}",
                                workflowSystemTask.getTaskType(),
                                task.getTaskId(),
                                workflow.getWorkflowId(),
                                e);
                    }
                }
                executionDAOFacade.updateTask(task);
            }
        }
        if (erroredTasks.isEmpty()) {
            try {
                if (raiseFinalized) {
                    notifyWorkflowStatusListener(workflow, WorkflowEventType.FINALIZED);
                }
                queueDAO.remove(DECIDER_QUEUE, workflow.getWorkflowId());
            } catch (Exception e) {
                LOGGER.error(
                        "Error removing workflow: {} from decider queue",
                        workflow.getWorkflowId(),
                        e);
            }
        }
        return erroredTasks;
    }

    @VisibleForTesting
    List<TaskModel> dedupAndAddTasks(WorkflowModel workflow, List<TaskModel> tasks) {
        Set<String> tasksInWorkflow =
                workflow.getTasks().stream()
                        .map(task -> task.getReferenceTaskName() + "_" + task.getRetryCount())
                        .collect(Collectors.toSet());

        List<TaskModel> dedupedTasks =
                tasks.stream()
                        .filter(
                                task ->
                                        !tasksInWorkflow.contains(
                                                task.getReferenceTaskName()
                                                        + "_"
                                                        + task.getRetryCount()))
                        .collect(Collectors.toList());

        workflow.getTasks().addAll(dedupedTasks);
        return dedupedTasks;
    }

    /**
     * @throws ConflictException if the workflow is in terminal state.
     */
    @Override
    public void pauseWorkflow(String workflowId) {
        try {
            executionLockService.acquireLock(workflowId, properties.getLockLeaseTime().toMillis());
            WorkflowModel.Status status = WorkflowModel.Status.PAUSED;
            WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, false);
            if (workflow.getStatus().isTerminal()) {
                throw new ConflictException(
                        "Workflow %s has ended, status cannot be updated.",
                        workflow.toShortString());
            }
            if (workflow.getStatus().equals(status)) {
                return;
            }
            workflow.setStatus(status);
            executionDAOFacade.updateWorkflow(workflow);

            notifyWorkflowStatusListener(workflow, WorkflowEventType.PAUSED);
        } finally {
            executionLockService.releaseLock(workflowId);
        }

        try {
            queueDAO.remove(DECIDER_QUEUE, workflowId);
        } catch (Exception e) {
            LOGGER.info(
                    "[pauseWorkflow] Error removing workflow: {} from decider queue",
                    workflowId,
                    e);
        }
    }

    /**
     * @param workflowId the workflow to be resumed
     * @throws IllegalStateException if the workflow is not in PAUSED state
     */
    @Override
    public void resumeWorkflow(String workflowId) {
        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, false);
        if (!workflow.getStatus().equals(WorkflowModel.Status.PAUSED)) {
            throw new IllegalStateException(
                    "The workflow "
                            + workflowId
                            + " is not PAUSED so cannot resume. "
                            + "Current status is "
                            + workflow.getStatus().name());
        }
        workflow.setStatus(WorkflowModel.Status.RUNNING);
        workflow.setLastRetriedTime(System.currentTimeMillis());
        queueDAO.push(
                DECIDER_QUEUE,
                workflow.getWorkflowId(),
                workflow.getPriority(),
                properties.getWorkflowOffsetTimeout().getSeconds());
        executionDAOFacade.updateWorkflow(workflow);
        notifyWorkflowStatusListener(workflow, WorkflowEventType.RESUMED);
        decide(workflowId);
    }

    /**
     * @param workflowId the id of the workflow
     * @param taskReferenceName the referenceName of the task to be skipped
     * @param skipTaskRequest the {@link SkipTaskRequest} object
     * @throws IllegalStateException
     */
    @Override
    public void skipTaskFromWorkflow(
            String workflowId, String taskReferenceName, SkipTaskRequest skipTaskRequest) {

        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);

        if (!workflow.getStatus().equals(WorkflowModel.Status.RUNNING)) {
            String errorMsg =
                    String.format(
                            "The workflow %s is not running so the task referenced by %s cannot be skipped",
                            workflowId, taskReferenceName);
            throw new IllegalStateException(errorMsg);
        }

        WorkflowTask workflowTask =
                workflow.getWorkflowDefinition().getTaskByRefName(taskReferenceName);
        if (workflowTask == null) {
            String errorMsg =
                    String.format(
                            "The task referenced by %s does not exist in the WorkflowDefinition %s",
                            taskReferenceName, workflow.getWorkflowName());
            throw new IllegalStateException(errorMsg);
        }

        workflow.getTasks()
                .forEach(
                        task -> {
                            if (task.getReferenceTaskName().equals(taskReferenceName)) {
                                String errorMsg =
                                        String.format(
                                                "The task referenced %s has already been processed, cannot be skipped",
                                                taskReferenceName);
                                throw new IllegalStateException(errorMsg);
                            }
                        });

        TaskModel taskToBeSkipped = new TaskModel();
        taskToBeSkipped.setTaskId(idGenerator.generate());
        taskToBeSkipped.setReferenceTaskName(taskReferenceName);
        taskToBeSkipped.setWorkflowInstanceId(workflowId);
        taskToBeSkipped.setWorkflowPriority(workflow.getPriority());
        taskToBeSkipped.setStatus(SKIPPED);
        taskToBeSkipped.setEndTime(System.currentTimeMillis());
        taskToBeSkipped.setTaskType(workflowTask.getName());
        taskToBeSkipped.setCorrelationId(workflow.getCorrelationId());
        if (skipTaskRequest != null) {
            // Note: If skipTaskRequest.getTaskInput/Output can contain sensitive data,
            // these would need encryption too. This is a potential future enhancement
            // if skipTask is a frequent path for sensitive data.
            taskToBeSkipped.setInputData(skipTaskRequest.getTaskInput());
            taskToBeSkipped.setOutputData(skipTaskRequest.getTaskOutput());
            taskToBeSkipped.setInputMessage(skipTaskRequest.getInputMessage()); // Corrected from getTaskInputMessage
            taskToBeSkipped.setOutputMessage(skipTaskRequest.getOutputMessage()); // Corrected from getTaskOutputMessage
        }
        executionDAOFacade.createTasks(Collections.singletonList(taskToBeSkipped));
        decide(workflow.getWorkflowId());
    }

    @Override
    public List<Workflow> getRunningWorkflows(String workflowName, int version) {
        return executionDAOFacade.getPendingWorkflowsByName(workflowName, version);
    }

    @Override
    public List<String> getWorkflows(String name, Integer version, Long startTime, Long endTime) {
        return executionDAOFacade.getWorkflowsByName(name, startTime, endTime).stream()
                .filter(workflow -> workflow.getWorkflowVersion() == version)
                .map(Workflow::getWorkflowId)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getRunningWorkflowIds(String workflowName, int version) {
        return executionDAOFacade.getRunningWorkflowIds(workflowName, version);
    }

    /** Records a metric for the "decide" process. */
    @Override
    public String startWorkflow(StartWorkflowInput input) {
        LOGGER.info("Starting workflow in WorkflowExecutorSecretOps {}", input);
        WorkflowDef workflowDefinition;

        if (input.getWorkflowDefinition() == null) {
            workflowDefinition =
                    metadataMapperService.lookupForWorkflowDefinition(
                            input.getName(), input.getVersion());
        } else {
            workflowDefinition = input.getWorkflowDefinition();
        }

        workflowDefinition = metadataMapperService.populateTaskDefinitions(workflowDefinition);

        Map<String, Object> workflowInput = input.getWorkflowInput();

        // --- NEW LOGIC: SECRET RESOLUTION AND ENCRYPTION FOR WORKFLOW START ---
        WorkflowDef processedWorkflowDefinition = workflowDefinition;
        Map<String, Object> processedWorkflowInput = workflowInput;

        if (workflowPreProcessor.containsSecrets(workflowDefinition, workflowInput)) {
            LOGGER.info(
                    "Secrets detected in workflow definition or input for workflow: {}. Resolving and Encrypting secrets...",
                    workflowDefinition.getName());
            // 1. Resolve secrets to plaintext (your existing WorkflowPreProcessor does this)
            processedWorkflowDefinition = workflowPreProcessor.processWorkflowDef(workflowDefinition);
            processedWorkflowInput = workflowPreProcessor.processMap(workflowInput);

            // 2. Encrypt plaintext secrets wrapped with metadata by WorkflowPreProcessor
            processedWorkflowDefinition = workflowInputEncryptor.encryptWorkflowDef(processedWorkflowDefinition);
            processedWorkflowInput = workflowInputEncryptor.encryptMap(processedWorkflowInput);

            LOGGER.info("Secrets resolved and encrypted for workflow: {}", workflowDefinition.getName());
        } else {
            LOGGER.info(
                    "No secrets detected in workflow definition or input for workflow: {}. Skipping secret resolution and encryption.",
                    workflowDefinition.getName());
        }
        // --- END NEW LOGIC ---

        String externalInputPayloadStoragePath = input.getExternalInputPayloadStoragePath();
        validateWorkflow(
                processedWorkflowDefinition,
                processedWorkflowInput,
                externalInputPayloadStoragePath);

        String workflowId =
                Optional.ofNullable(input.getWorkflowId()).orElseGet(idGenerator::generate);

        WorkflowModel workflow = new WorkflowModel();
        workflow.setWorkflowId(workflowId);
        workflow.setCorrelationId(input.getCorrelationId());
        workflow.setPriority(input.getPriority() == null ? 0 : input.getPriority());
        workflow.setWorkflowDefinition(processedWorkflowDefinition); // Use processed & encrypted def
        workflow.setStatus(WorkflowModel.Status.RUNNING);
        workflow.setParentWorkflowId(input.getParentWorkflowId());
        workflow.setParentWorkflowTaskId(input.getParentWorkflowTaskId());
        workflow.setOwnerApp(WorkflowContext.get().getClientApp());
        workflow.setCreateTime(System.currentTimeMillis());
        workflow.setUpdatedBy(null);
        workflow.setUpdatedTime(null);
        workflow.setEvent(input.getEvent());
        workflow.setTaskToDomain(input.getTaskToDomain());
        workflow.setVariables(processedWorkflowDefinition.getVariables());

        if (processedWorkflowInput != null && !processedWorkflowInput.isEmpty()) {
            Map<String, Object> parsedInput =
                    parametersUtils.getWorkflowInput(
                            processedWorkflowDefinition, processedWorkflowInput); // parametersUtils will work with encrypted map
            workflow.setInput(parsedInput);
        } else {
            workflow.setExternalInputPayloadStoragePath(externalInputPayloadStoragePath);
        }

        try {
            createAndEvaluate(workflow);
            Monitors.recordWorkflowStartSuccess(
                    workflow.getWorkflowName(),
                    String.valueOf(workflow.getWorkflowVersion()),
                    workflow.getOwnerApp());
            return workflowId;
        } catch (Exception e) {
            Monitors.recordWorkflowStartError(
                    workflowDefinition.getName(), WorkflowContext.get().getClientApp());
            LOGGER.error("Unable to start workflow: {}", workflowDefinition.getName(), e);

            try {
                executionDAOFacade.removeWorkflow(workflowId, false);
            } catch (Exception rwe) {
                LOGGER.error("Could not remove the workflowId: " + workflowId, rwe);
            }
            throw e;
        }
    }

    private void createAndEvaluate(WorkflowModel workflow) {
        if (!executionLockService.acquireLock(workflow.getWorkflowId(), properties.getLockLeaseTime().toMillis())) {
            throw new TransientException("Error acquiring lock when creating workflow: {}");
        }
        try {
            executionDAOFacade.createWorkflow(workflow);
            LOGGER.debug(
                    "A new instance of workflow: {} created with id: {}",
                    workflow.getWorkflowName(),
                    workflow.getWorkflowId());
            executionDAOFacade.populateWorkflowAndTaskPayloadData(workflow);
            notifyWorkflowStatusListener(workflow, WorkflowEventType.STARTED);
            decide(workflow);
        } finally {
            executionLockService.releaseLock(workflow.getWorkflowId());
        }
    }

    /**
     * Performs validations for starting a workflow
     *
     * @throws IllegalArgumentException if the validation fails.
     */
    private void validateWorkflow(
            WorkflowDef workflowDef,
            Map<String, Object> workflowInput,
            String externalStoragePath) {
        if (workflowInput == null && StringUtils.isBlank(externalStoragePath)) {
            LOGGER.error("The input for the workflow '{}' cannot be NULL", workflowDef.getName());
            Monitors.recordWorkflowStartError(
                    workflowDef.getName(), WorkflowContext.get().getClientApp());

            throw new IllegalArgumentException("NULL input passed when starting workflow");
        }
    }

    private void notifyWorkflowStatusListener(WorkflowModel workflow, WorkflowEventType event) {
        try {
            switch (event) {
                case STARTED:
                    workflowStatusListener.onWorkflowStartedIfEnabled(workflow);
                    break;
                case RERAN:
                    workflowStatusListener.onWorkflowRerunIfEnabled(workflow);
                    break;
                case RETRIED:
                    workflowStatusListener.onWorkflowRetriedIfEnabled(workflow);
                    break;
                case PAUSED:
                    workflowStatusListener.onWorkflowPausedIfEnabled(workflow);
                    break;
                case RESUMED:
                    workflowStatusListener.onWorkflowResumedIfEnabled(workflow);
                    break;
                case RESTARTED:
                    workflowStatusListener.onWorkflowRestartedIfEnabled(workflow);
                    break;
                case COMPLETED:
                    workflowStatusListener.onWorkflowCompletedIfEnabled(workflow);
                    break;
                case TERMINATED:
                    workflowStatusListener.onWorkflowTerminatedIfEnabled(workflow);
                    break;
                case FINALIZED:
                    workflowStatusListener.onWorkflowFinalizedIfEnabled(workflow);
                    break;
                default:
                    return;
            }
        } catch (Exception e) {
            LOGGER.error(
                    "Error while notifying WorkflowStatusListener for workflow: {}",
                    workflow.getWorkflowId(),
                    e);
        }
    }
}