package com.ywdrtt.conductor;

import com.netflix.conductor.common.metadata.workflow.StartWorkflowInput;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.run.Workflow as WorkflowModel;
import com.netflix.conductor.core.WorkflowContext;
import com.netflix.conductor.core.config.ConductorProperties;
import com.netflix.conductor.core.dal.ExecutionDAOFacade;
import com.netflix.conductor.core.events.EventQueuesManager;
import com.netflix.conductor.core.execution.IdGenerator;
import com.netflix.conductor.core.execution.ParametersUtils;
import com.netflix.conductor.core.execution.tasks.SystemTaskRegistry;
import com.netflix.conductor.core.metadata.MetadataMapperService;
import com.netflix.conductor.metrics.Monitors;
import com.netflix.conductor.preprocessor.WorkflowPreProcessor; // Import your preprocessor
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern; // Not strictly needed here anymore, but leaving for context if existed

@Service
public class WorkflowExecutorOps {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowExecutorOps.class);

    private final IdGenerator idGenerator;
    private final ParametersUtils parametersUtils;
    private final MetadataMapperService metadataMapperService;
    private final ExecutionDAOFacade executionDAOFacade;
    private final EventQueuesManager eventQueuesManager;
    private final SystemTaskRegistry systemTaskRegistry;
    private final ConductorProperties properties;

    private final WorkflowPreProcessor workflowPreProcessor; // Inject the preprocessor

    @Autowired
    public WorkflowExecutorOps(IdGenerator idGenerator,
                               ParametersUtils parametersUtils,
                               MetadataMapperService metadataMapperService,
                               ExecutionDAOFacade executionDAOFacade,
                               EventQueuesManager eventQueuesManager,
                               SystemTaskRegistry systemTaskRegistry,
                               ConductorProperties properties,
                               WorkflowPreProcessor workflowPreProcessor) { // Autowire it
        this.idGenerator = idGenerator;
        this.parametersUtils = parametersUtils;
        this.metadataMapperService = metadataMapperService;
        this.executionDAOFacade = executionDAOFacade;
        this.eventQueuesManager = eventQueuesManager;
        this.systemTaskRegistry = systemTaskRegistry;
        this.properties = properties;
        this.workflowPreProcessor = workflowPreProcessor; // Assign it
    }


    // This method assumes it's an @Override from an interface in Conductor
    // Ensure the interface has this method signature.
    public String startWorkflow(StartWorkflowInput input) {
        WorkflowDef workflowDefinition;

        if (input.getWorkflowDefinition() == null) {
            workflowDefinition =
                    metadataMapperService.lookupForWorkflowDefinition(
                            input.getName(), input.getVersion());
        } else {
            workflowDefinition = input.getWorkflowDefinition();
        }

        workflowDefinition = metadataMapperService.populateTaskDefinitions(workflowDefinition);

        // --- NEW: SECRET INJECTION LOGIC FOR START WORKFLOW ---
        Map<String, Object> workflowInput = input.getWorkflowInput();
        WorkflowDef processedWorkflowDefinition = workflowDefinition;
        Map<String, Object> processedWorkflowInput = workflowInput;

        // Perform a quick check if secrets are present to avoid unnecessary processing
        if (workflowPreProcessor.containsSecrets(workflowDefinition, workflowInput)) {
            LOGGER.info("Secrets detected in workflow definition or input for workflow: {}. Resolving secrets...", workflowDefinition.getName());
            // Use the new JsonNode-based processing for WorkflowDef
            processedWorkflowDefinition = workflowPreProcessor.processWorkflowDefOptimized(workflowDefinition);
            // Use the new iterative map processing for workflow input
            processedWorkflowInput = workflowPreProcessor.processMapIterative(workflowInput);
            LOGGER.info("Secrets resolved for workflow: {}", workflowDefinition.getName());
        } else {
            LOGGER.debug("No secrets detected in workflow definition or input for workflow: {}. Skipping secret resolution.", workflowDefinition.getName());
        }
        // --- END NEW LOGIC ---

        // perform validations using the processed definition and input
        String externalInputPayloadStoragePath = input.getExternalInputPayloadStoragePath();
        validateWorkflow(processedWorkflowDefinition, processedWorkflowInput, externalInputPayloadStoragePath);


        // Generate ID if it's not present
        String workflowId =
                Optional.ofNullable(input.getWorkflowId()).orElseGet(idGenerator::generate);

        // Persist the Workflow
        WorkflowModel workflow = new WorkflowModel();
        workflow.setWorkflowId(workflowId);
        workflow.setCorrelationId(input.getCorrelationId());
        workflow.setPriority(input.getPriority() == null ? 0 : input.getPriority());
        workflow.setWorkflowDefinition(processedWorkflowDefinition); // Use processed definition
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

        if (processedWorkflowInput != null && !processedWorkflowInput.isEmpty()) { // Use processed input
            Map<String, Object> parsedInput =
                    parametersUtils.getWorkflowInput(processedWorkflowDefinition, processedWorkflowInput); // Use processed def and input
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

    // This method assumes it's an @Override from an interface in Conductor
    // Ensure the interface has this method signature.
    public void rerun(String workflowId, String rerunFromTaskId, Map<String, Object> taskInput, String correlationId) {
        // --- NEW: SECRET INJECTION LOGIC FOR RERUN ---
        Map<String, Object> processedTaskInput = taskInput;
        // Re-using containsSecretsInMap logic from PreProcessor (via general containsSecrets for a Map)
        // Need to convert taskInput to a list or map containing it for containsSecrets
        // Or create a dedicated containsSecretsInMap for the preprocessor
        if (taskInput != null && workflowPreProcessor.containsSecrets(null, taskInput)) { // Pass null for WorkflowDef if only checking input map
            LOGGER.info("Secrets detected in rerun task input for workflowId: {}. Resolving secrets...", workflowId);
            processedTaskInput = workflowPreProcessor.processMapIterative(taskInput);
            LOGGER.info("Secrets resolved for rerun task input for workflowId: {}", workflowId);
        } else {
            LOGGER.debug("No secrets detected in rerun task input for workflowId: {}. Skipping secret resolution.", workflowId);
        }
        // --- END NEW LOGIC ---

        // Call the original rerun logic with the processed input
        executionDAOFacade.rerunWorkflow(workflowId, rerunFromTaskId, processedTaskInput, correlationId);
    }


    // --- Other methods of WorkflowExecutorOps remain unchanged ---

    private void createAndEvaluate(WorkflowModel workflow) {
        // Assumed to be original Conductor logic, using the 'workflow' object with processed data.
        executionDAOFacade.createWorkflow(workflow);
        // ... rest of the logic ...
    }

    private void validateWorkflow(WorkflowDef workflowDefinition, Map<String, Object> workflowInput, String externalInputPayloadStoragePath) {
        // Assumed to be original Conductor validation logic, using the processed definition and input.
        // ... validation logic ...
    }
}
