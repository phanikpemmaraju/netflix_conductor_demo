package com.ywdrtt.conductor.secuity;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowTask;
import com.netflix.conductor.core.annotations.ConditionalOnProperty;
import com.netflix.conductor.core.secrets.SecretManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ConditionalOnProperty(name = "conductor.secrets.enabled", havingValue = "true")
public class Process {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowPreProcessor.class);
    private static final Pattern SECRET_PATTERN = Pattern.compile("\$workflow\.secrets\.([a-zA-Z0-9_-]+)");

    private final SecretManagerService secretManagerService;
    private final ObjectMapper objectMapper;

    @Inject
    public WorkflowPreProcessor(SecretManagerService secretManagerService, ObjectMapper objectMapper) {
        this.secretManagerService = secretManagerService;
        this.objectMapper = objectMapper;
    }

    public boolean containsSecrets(WorkflowDef workflowDef, Map<String, Object> workflowInput) {
        if (workflowInput != null && containsSecretsInMap(workflowInput)) {
            return true;
        }
        if (workflowDef.getTasks() != null) {
            for (WorkflowTask task : workflowDef.getTasks()) {
                if (task.getInputParameters() != null && containsSecretsInMap(task.getInputParameters())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsSecretsInMap(Map<String, Object> map) {
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(map);
        while (!stack.isEmpty()) {
            Object current = stack.pop();
            if (current instanceof Map) {
                for (Object val : ((Map<?, ?>) current).values()) {
                    if (val instanceof String && SECRET_PATTERN.matcher((String) val).find()) return true;
                    if (val instanceof Map || val instanceof List) stack.push(val);
                }
            } else if (current instanceof List) {
                for (Object item : (List<?>) current) {
                    if (item instanceof String && SECRET_PATTERN.matcher((String) item).find()) return true;
                    if (item instanceof Map || item instanceof List) stack.push(item);
                }
            }
        }
        return false;
    }

    public WorkflowDef processWorkflowDef(WorkflowDef originalWorkflowDef) {
        try {
            WorkflowDef defCopy = objectMapper.readValue(
                    objectMapper.writeValueAsString(originalWorkflowDef), WorkflowDef.class);
            if (defCopy.getTasks() != null) {
                for (WorkflowTask task : defCopy.getTasks()) {
                    if (task.getInputParameters() != null) {
                        task.setInputParameters((Map<String, Object>) deepProcessValue(task.getInputParameters()));
                    }
                }
            }
            return defCopy;
        } catch (Exception e) {
            throw new RuntimeException("Error processing workflow definition", e);
        }
    }

    private Object deepProcessValue(Object value) {
        if (value instanceof String) {
            return resolveSecretsInString((String) value);
        } else if (value instanceof Map) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(entry.getKey().toString(), deepProcessValue(entry.getValue()));
            }
            return result;
        } else if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                result.add(deepProcessValue(item));
            }
            return result;
        }
        return value;
    }

    private String resolveSecretsInString(String input) {
        Matcher matcher = SECRET_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String secretName = matcher.group(1);
            Optional<String> value = secretManagerService.getSecret(secretName);
            if (!value.isPresent()) {
                throw new RuntimeException("Secret not found: " + secretName);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value.get()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

