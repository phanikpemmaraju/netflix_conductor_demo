/*
 * Copyright 2025 Conductor Authors.
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.core.secretmanager.SecretManagerService;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "conductor.secrets.enabled", havingValue = "true")
public class WorkflowPreProcessor {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowPreProcessor.class);
    final Map<String, String> secretCache = new ConcurrentHashMap<>();

    private static final Pattern SECRET_PATTERN =
            Pattern.compile("\\$\\{workflow\\.secrets\\.([a-zA-Z0-9_-]+)}");

    private final SecretManagerService secretManagerService; // NEW: Use SecretManagerService
    private final ObjectMapper objectMapper;

    public WorkflowPreProcessor(
            SecretManagerService secretManagerService,
            ObjectMapper objectMapper) { // Constructor updated
        this.secretManagerService = secretManagerService;
        this.objectMapper = objectMapper;
    }

    /**
     * Checks if the given workflow definition or its input parameters contain any secret
     * placeholders. This is a quick check to avoid unnecessary full processing.
     *
     * @param workflowDef The workflow definition.
     * @param workflowInput The workflow instance input.
     * @return true if any secret placeholder is found, false otherwise.
     */
    public boolean containsSecrets(WorkflowDef workflowDef, Map<String, Object> workflowInput) {
        // Check workflow definition input parameters
        logger.info(
                "Checking if workflow contains secrets : {} ; workflowInput {}",
                workflowDef,
                workflowInput);
        if (workflowDef.getInputParameters() != null) {
            for (Object param : workflowDef.getInputParameters()) {
                if (param instanceof String && SECRET_PATTERN.matcher((String) param).find()) {
                    return true;
                }
            }
        }

        // Check task input parameters within the workflow definition
        if (workflowDef.getTasks() != null) {
            logger.info("Checking if workflow contains tasks ??? ");
            for (com.netflix.conductor.common.metadata.workflow.WorkflowTask task :
                    workflowDef.getTasks()) {
                logger.info("Workflow contains task {}", task);
                if (task.getInputParameters() != null
                        && containsSecretsInMap(task.getInputParameters())) {
                    return true;
                }
            }
        }

        // Check workflow instance input
        if (containsSecretsInMap(workflowInput)) {
            return true;
        }

        return false;
    }

    // Helper for checking secrets in a Map (recursive)
    @SuppressWarnings("unchecked")
    public boolean containsSecretsInMap(Map<String, Object> map) {
        logger.info("Checking if workflow Task contains secrets in map {}", map);
        if (map == null) return false;
        for (Object value : map.values()) {
            if (value instanceof String && SECRET_PATTERN.matcher((String) value).find()) {
                return true;
            } else if (value instanceof Map && containsSecretsInMap((Map<String, Object>) value)) {
                return true;
            } else if (value instanceof List) {
                for (Object item : (List<Object>) value) {
                    if (item instanceof String && SECRET_PATTERN.matcher((String) item).find()) {
                        return true;
                    } else if (item instanceof Map
                            && containsSecretsInMap((Map<String, Object>) item)) {
                        return true;
                    } else if (item instanceof List && containsSecretsInList((List<Object>) item)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // Helper for checking secrets in a List (recursive)
    @SuppressWarnings("unchecked")
    private boolean containsSecretsInList(List<Object> list) {
        if (list == null) return false;
        for (Object item : list) {
            if (item instanceof String && SECRET_PATTERN.matcher((String) item).find()) {
                return true;
            } else if (item instanceof Map && containsSecretsInMap((Map<String, Object>) item)) {
                return true;
            } else if (item instanceof List && containsSecretsInList((List<Object>) item)) {
                return true;
            }
        }
        return false;
    }

    public Map<String, Object> processMap(Map<String, Object> inputMap) {
        if (inputMap == null || inputMap.isEmpty()) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> processedMap = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : inputMap.entrySet()) {
            processedMap.put(entry.getKey(), deepProcessValue(entry.getValue()));
        }
        return processedMap;
    }

    @SuppressWarnings("unchecked")
    private Object deepProcessValue(Object value) {
        if (value instanceof String) {
            return resolveSecretsInString((String) value);
        } else if (value instanceof Map) {
            return processMap((Map<String, Object>) value);
        } else if (value instanceof List) {
            return ((List<Object>) value)
                    .stream().map(this::deepProcessValue).collect(Collectors.toList());
        }
        return value;
    }

    private String resolveSecretsInString(String inputString) {
        if (!inputString.contains("workflow.secrets")) {
            return inputString;
        }
        Matcher matcher = SECRET_PATTERN.matcher(inputString);
        StringBuilder builder = new StringBuilder();

        while (matcher.find()) {
            String secretName = matcher.group(1);
            logger.debug("Found secret placeholder: ${}. Attempting to resolve.", secretName);

            // NEW: Call SecretManagerService directly
            Optional<String> secretValueOptional = secretManagerService.getSecret(secretName);

            if (secretValueOptional.isPresent()) {
                String secretValue = secretValueOptional.get();
                matcher.appendReplacement(builder, Matcher.quoteReplacement(secretValue));
            } else {
                logger.error(
                        "Required secret '{}' not found in SecretManagerService. Failing workflow submission.",
                        secretName);
                throw new RuntimeException("Required secret '" + secretName + "' not found.");
            }

            //            matcher.appendReplacement(builder, Matcher.quoteReplacement(secretValue));
            logger.info("Successfully replaced secret: {}", secretName);
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    public WorkflowDef processWorkflowDef(WorkflowDef originalWorkflowDef) {
        logger.info(
                "Starting workflow definition pre-processing for workflow: {}",
                originalWorkflowDef.getName());

        WorkflowDef processedWorkflowDef;
        try {
            processedWorkflowDef =
                    objectMapper.readValue(
                            objectMapper.writeValueAsString(originalWorkflowDef),
                            WorkflowDef.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deep copy WorkflowDef", e);
        }

        if (processedWorkflowDef.getInputParameters() != null) {
            processedWorkflowDef.setInputParameters(
                    processedWorkflowDef.getInputParameters().stream()
                            .map(this::deepProcessValue)
                            .map(Object::toString)
                            .collect(Collectors.toList()));
        }

        if (processedWorkflowDef.getTasks() != null) {
            for (com.netflix.conductor.common.metadata.workflow.WorkflowTask task :
                    processedWorkflowDef.getTasks()) {
                if (task.getInputParameters() != null) {
                    task.setInputParameters(processMap(task.getInputParameters()));
                }
            }
        }

        logger.info(
                "Workflow definition pre-processing complete for workflow: {}",
                originalWorkflowDef.getName());
        return processedWorkflowDef;
    }
}