package com.ywdrtt.conductor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WorkflowPreProcessor {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowPreProcessor.class);
    // Regex pattern for ${workflow.secrets.secretName}
    private static final Pattern SECRET_PATTERN = Pattern.compile("\\$workflow\\.secrets\\.([a-zA-Z0-9_-]+)");

    private final SecretManagerService secretManagerService;
    private final ObjectMapper objectMapper;

    // Thread-safe cache for resolved secrets
    private final Map<String, String> secretCache; // Using ConcurrentHashMap for simplicity, consider Caffeine for advanced needs

    public WorkflowPreProcessor(SecretManagerService secretManagerService) {
        this.secretManagerService = secretManagerService;
        this.objectMapper = new ObjectMapper();
        this.secretCache = new ConcurrentHashMap<>();
    }

    /**
     * Resolves a secret value from the SecretManagerService, utilizing an in-memory cache.
     * If the secret is not found, a RuntimeException is thrown.
     * @param secretName The name of the secret to resolve.
     * @return The resolved secret value.
     * @throws RuntimeException if the secret is not found.
     */
    private String resolveSecret(String secretName) {
        return secretCache.computeIfAbsent(secretName, k -> {
            logger.debug("Attempting to resolve secret from SecretManagerService: {}", k);
            return secretManagerService.getSecretValue(k)
                    .orElseThrow(() -> {
                        logger.error("Required secret '{}' not found in SecretManagerService. Failing workflow submission.", k);
                        return new RuntimeException("Required secret '" + k + "' not found.");
                    });
        });
    }

    /**
     * Replaces all occurrences of secret placeholders (${workflow.secrets.secretName}) in a given string
     * with their resolved values.
     *
     * @param inputString The string possibly containing secret placeholders.
     * @return The string with secrets resolved, or the original string if no secrets were found.
     * @throws RuntimeException if a required secret is not found.
     */
    private String resolveSecretsInString(String inputString) {
        // Quick check to avoid regex overhead if no secret pattern is present
        if (!inputString.contains("workflow.secrets.")) {
            return inputString;
        }

        Matcher matcher = SECRET_PATTERN.matcher(inputString);
        StringBuffer sb = new StringBuffer();
        boolean foundSecret = false;

        while (matcher.find()) {
            String secretName = matcher.group(1);
            String secretValue = resolveSecret(secretName); // Use the cached resolver
            matcher.appendReplacement(sb, Matcher.quoteReplacement(secretValue));
            logger.debug("Successfully replaced secret: {}", secretName); // Debug: log replaced secret name
            foundSecret = true;
        }
        matcher.appendTail(sb);

        if (foundSecret) {
            // Log with redaction to avoid exposing secrets in logs
            logger.info("Secrets replaced in string (original part): {}", inputString.replaceAll(SECRET_PATTERN.pattern(), "[REDACTED]"));
        }
        return sb.toString();
    }

    /**
     * Checks if the given workflow definition or its input parameters contain any secret placeholders.
     * This uses an iterative approach for robustness.
     *
     * @param workflowDef The workflow definition.
     * @param workflowInput The workflow instance input.
     * @return true if any secret placeholder is found, false otherwise.
     */
    @SuppressWarnings("unchecked")
    public boolean containsSecrets(WorkflowDef workflowDef, Map<String, Object> workflowInput) {
        // Use a stack for iterative traversal
        Deque<Object> stack = new ArrayDeque<>();

        // 1. Check workflow definition's input parameters
        if (workflowDef.getInputParameters() != null) {
            stack.push(workflowDef.getInputParameters());
        }
        // 2. Check tasks' input parameters within the workflow definition
        if (workflowDef.getTasks() != null) {
            workflowDef.getTasks().forEach(task -> {
                if (task.getInputParameters() != null) {
                    stack.push(task.getInputParameters());
                }
            });
        }
        // 3. Check workflow instance input
        if (workflowInput != null) {
            stack.push(workflowInput);
        }

        while (!stack.isEmpty()) {
            Object current = stack.pop();
            if (current instanceof String str) {
                if (SECRET_PATTERN.matcher(str).find()) {
                    return true;
                }
            } else if (current instanceof Map<?, ?> map) {
                for (Object value : map.values()) {
                    if (value instanceof String str) {
                        if (SECRET_PATTERN.matcher(str).find()) {
                            return true;
                        }
                    } else if (value instanceof Map) {
                        stack.push(value);
                    } else if (value instanceof List) {
                        stack.push(value);
                    }
                }
            } else if (current instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String str) {
                        if (SECRET_PATTERN.matcher(str).find()) {
                            return true;
                        }
                    } else if (item instanceof Map) {
                        stack.push(item);
                    } else if (item instanceof List) {
                        stack.push(item);
                    }
                }
            }
        }
        return false;
    }


    /**
     * Iteratively processes a map, replacing secret placeholders in string values.
     * Creates a deep copy of the input map to avoid modifying the original.
     *
     * @param input The input map potentially containing nested structures.
     * @return A new map with secrets resolved.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> processMapIterative(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return new LinkedHashMap<>();
        }

        // Create a deep copy to work on, using ObjectMapper for robustness
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(objectMapper.writeValueAsString(input), LinkedHashMap.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deep copy input map for secret processing", e);
        }

        Deque<Object> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            Object current = stack.pop();
            if (current instanceof Map<?, ?> map) {
                Map<String, Object> currentMap = (Map<String, Object>) map;
                for (Map.Entry<String, Object> entry : currentMap.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof String str) { // No need for contains check here, resolveSecretsInString handles it
                        entry.setValue(resolveSecretsInString(str));
                    } else if (value instanceof Map) {
                        stack.push(value);
                    } else if (value instanceof List) {
                        stack.push(value);
                    }
                }
            } else if (current instanceof List<?> list) {
                List<Object> currentList = (List<Object>) list;
                for (int i = 0; i < currentList.size(); i++) {
                    Object item = currentList.get(i);
                    if (item instanceof String str) {
                        currentList.set(i, resolveSecretsInString(str));
                    } else if (item instanceof Map) {
                        stack.push(item);
                    } else if (item instanceof List) {
                        stack.push(item);
                    }
                }
            }
        }
        return root;
    }

    /**
     * Iteratively processes a list, replacing secret placeholders in string values.
     * This is a helper, typically called from processMapIterative.
     *
     * @param inputList The input list potentially containing nested structures.
     * @return A new list with secrets resolved.
     */
    @SuppressWarnings("unchecked")
    public List<Object> processListIterative(List<Object> inputList) {
        if (inputList == null || inputList.isEmpty()) {
            return new ArrayList<>();
        }

        // Create a deep copy using ObjectMapper
        List<Object> rootList;
        try {
            rootList = objectMapper.readValue(objectMapper.writeValueAsString(inputList), ArrayList.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deep copy input list for secret processing", e);
        }

        Deque<Object> stack = new ArrayDeque<>();
        stack.push(rootList);

        while (!stack.isEmpty()) {
            Object current = stack.pop();
            if (current instanceof List<?> list) {
                List<Object> currentList = (List<Object>) list;
                for (int i = 0; i < currentList.size(); i++) {
                    Object item = currentList.get(i);
                    if (item instanceof String str) {
                        currentList.set(i, resolveSecretsInString(str));
                    } else if (item instanceof Map) {
                        stack.push(item);
                    } else if (item instanceof List) {
                        stack.push(item);
                    }
                }
            } else if (current instanceof Map<?, ?> map) {
                Map<String, Object> currentMap = (Map<String, Object>) map;
                for (Map.Entry<String, Object> entry : currentMap.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof String str) {
                        entry.setValue(resolveSecretsInString(str));
                    } else if (value instanceof Map) {
                        stack.push(value);
                    } else if (value instanceof List) {
                        stack.push(value);
                    }
                }
            }
        }
        return rootList;
    }


    /**
     * Processes the entire WorkflowDef object for secret placeholders using Jackson's JsonNode.
     * This provides a robust way to traverse and modify the workflow definition.
     *
     * @param originalWorkflowDef The original workflow definition.
     * @return A new WorkflowDef instance with all secret placeholders resolved.
     * @throws RuntimeException if deep copying or secret resolution fails.
     */
    public WorkflowDef processWorkflowDefOptimized(WorkflowDef originalWorkflowDef) {
        logger.info("Starting workflow definition pre-processing for workflow: {}", originalWorkflowDef.getName());

        try {
            // Convert original WorkflowDef to JsonNode
            JsonNode workflowDefNode = objectMapper.valueToTree(originalWorkflowDef);

            // Process the JsonNode tree for secrets
            JsonNode processedWorkflowDefNode = processJsonNode(workflowDefNode);

            // Convert the processed JsonNode back to WorkflowDef
            WorkflowDef processedWorkflowDef = objectMapper.treeToValue(processedWorkflowDefNode, WorkflowDef.class);

            logger.info("Workflow definition pre-processing complete for workflow: {}", originalWorkflowDef.getName());
            return processedWorkflowDef;

        } catch (Exception e) {
            logger.error("Failed to process WorkflowDef '{}' for secrets: {}", originalWorkflowDef.getName(), e.getMessage(), e);
            throw new RuntimeException("Failed to process WorkflowDef '" + originalWorkflowDef.getName() + "' for secrets", e);
        }
    }

    /**
     * Recursively traverses a JsonNode tree, replacing secret placeholders in TextNode values.
     * Creates new JsonNode objects for modifications to maintain immutability of the original tree.
     *
     * @param node The current JsonNode to process.
     * @return A new JsonNode with secrets resolved, or the original node if no changes needed.
     */
    private JsonNode processJsonNode(JsonNode node) {
        if (node == null) {
            return null;
        }

        if (node.isTextual()) {
            // Only process if it potentially contains a secret
            String text = node.asText();
            if (text.contains("workflow.secrets.")) {
                return new TextNode(resolveSecretsInString(text));
            }
            return node; // No secrets, return original node
        } else if (node.isObject()) {
            ObjectNode originalObjectNode = (ObjectNode) node;
            ObjectNode processedObjectNode = objectMapper.createObjectNode();
            boolean modified = false;

            Iterator<Map.Entry<String, JsonNode>> fields = originalObjectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String fieldName = field.getKey();
                JsonNode originalValue = field.getValue();
                JsonNode processedValue = processJsonNode(originalValue); // Recursive call

                processedObjectNode.set(fieldName, processedValue);
                if (processedValue != originalValue) { // Check if the child node was modified
                    modified = true;
                }
            }
            return modified ? processedObjectNode : originalObjectNode; // Return new node only if modified
        } else if (node.isArray()) {
            ArrayNode originalArrayNode = (ArrayNode) node;
            ArrayNode processedArrayNode = objectMapper.createArrayNode();
            boolean modified = false;

            for (JsonNode element : originalArrayNode) {
                JsonNode processedElement = processJsonNode(element); // Recursive call
                processedArrayNode.add(processedElement);
                if (processedElement != element) { // Check if the child element was modified
                    modified = true;
                }
            }
            return modified ? processedArrayNode : originalArrayNode; // Return new node only if modified
        }
        // For other types (numeric, boolean, null), return as is
        return node;
    }
}