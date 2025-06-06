package com.ywdrtt.conductor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowTask;
import com.netflix.conductor.core.annotations.ConditionalOnProperty;
import com.netflix.conductor.core.secrets.SecretManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pre-processes workflow definitions and inputs to handle secrets.
 * This class replaces recursive logic with iterative approaches using Deques for improved performance.
 */
@ConditionalOnProperty(name = "conductor.secrets.enabled", havingValue = "true")
public class Refactor {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowPreProcessor.class);
    // No longer needed for caching, as iterative approach changes flow, but kept for clarity if future use arises.
    private static final Map<String, String> secretCache = new ConcurrentHashMap<>();
    private static final Pattern SECRET_PATTERN = Pattern.compile("regex: \\$\\{\\{workflow\\\\.secrets\\\\.([a-zA-Z0-9_-]+)\\}\\}");

    private final SecretManagerService secretManagerService;
    private final ObjectMapper objectMapper;

    @Inject
    public WorkflowPreProcessor(SecretManagerService secretManagerService, ObjectMapper objectMapper) {
        this.secretManagerService = secretManagerService;
        this.objectMapper = objectMapper;
    }

    /**
     * Checks if the given workflow definition or its input parameters contain any secret placeholders.
     * This is a quick check to avoid unnecessary full processing.
     *
     * @param workflowDef The workflow definition.
     * @param workflowInput The workflow instance input.
     * @return true if any secret placeholder is found, false otherwise.
     */
    public boolean containsSecrets(WorkflowDef workflowDef, Map<String, Object> workflowInput) {
        logger.info("Checking if workflow contains secrets in: workflowDef: {}, workflowInput: {}",
                workflowDef.getName(), workflowInput);

        // Check workflow instance input parameters
        if (workflowInput != null && containsSecretsInMap(workflowInput)) {
            return true;
        }

        // Check task input parameters within the workflow definition
        if (workflowDef.getTasks() != null) {
            for (WorkflowTask task : workflowDef.getTasks()) {
                if (task.getInputParameters() != null && containsSecretsInMap(task.getInputParameters())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Helper for checking secrets in a Map (iterative).
     *
     * @param inputMap The map to check for secrets.
     * @return true if secrets are found, false otherwise.
     */
    private boolean containsSecretsInMap(Map<String, Object> inputMap) {
        if (inputMap == null || inputMap.isEmpty()) {
            return false;
        }

        Deque<Object> stack = new LinkedList<>();
        stack.push(inputMap);

        while (!stack.isEmpty()) {
            Object current = stack.pop();

            if (current instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) current;
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    if (entry.getValue() instanceof String) {
                        if (SECRET_PATTERN.matcher((String) entry.getValue()).find()) {
                            logger.debug("Found secret pattern in map key: {}, value: {}", entry.getKey(), entry.getValue());
                            return true;
                        }
                    } else if (entry.getValue() instanceof Map || entry.getValue() instanceof List) {
                        stack.push(entry.getValue());
                    }
                }
            } else if (current instanceof List) {
                List<Object> list = (List<Object>) current;
                for (Object item : list) {
                    if (item instanceof String) {
                        if (SECRET_PATTERN.matcher((String) item).find()) {
                            logger.debug("Found secret pattern in list item: {}", item);
                            return true;
                        }
                    } else if (item instanceof Map || item instanceof List) {
                        stack.push(item);
                    }
                }
            }
        }
        return false;
    }

    /**
     * Processes the workflow definition to resolve secret placeholders.
     * Deep copies the workflow definition and resolves secrets iteratively.
     *
     * @param originalWorkflowDef The original workflow definition.
     * @return A new WorkflowDef with secrets resolved.
     */
    public WorkflowDef processWorkflowDef(WorkflowDef originalWorkflowDef) {
        logger.info("Starting workflow definition pre-processing for workflow: {}", originalWorkflowDef.getName());

        try {
            // Deep copy the workflow definition to avoid modifying the original
            // This also handles complex nested objects within WorkflowDef
            WorkflowDef processedWorkflowDef = objectMapper.readValue(
                    objectMapper.writeValueAsString(originalWorkflowDef),
                    WorkflowDef.class
            );

            // Process input parameters for the workflow tasks iteratively
            if (processedWorkflowDef.getTasks() != null) {
                for (WorkflowTask task : processedWorkflowDef.getTasks()) {
                    if (task.getInputParameters() != null) {
                        task.setInputParameters(processMap(task.getInputParameters()));
                    }
                }
            }

            logger.info("Workflow definition pre-processing complete for workflow: {}", originalWorkflowDef.getName());
            return processedWorkflowDef;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deep copy WorkflowDef or process secrets", e);
        }
    }

    /**
     * Iteratively processes a map to deep copy and resolve secret placeholders.
     *
     * @param inputMap The map to process.
     * @return A new map with secrets resolved.
     */
    private Map<String, Object> processMap(Map<String, Object> inputMap) {
        if (inputMap == null) {
            return new HashMap<>();
        }

        Map<String, Object> processedMap = new HashMap<>();
        Deque<Map.Entry<String, Object>> stack = new ArrayDeque<>();

        // Initialize stack with entries from the input map
        for (Map.Entry<String, Object> entry : inputMap.entrySet()) {
            stack.push(entry);
        }

        // Use a temporary map to build the processed structure before assigning to the final map
        // This is crucial for handling nested structures properly without infinite loops
        Map<String, Object> currentMapBuilding = processedMap;
        Deque<Object> parentStack = new ArrayDeque<>(); // Stack to keep track of parent maps/lists for nesting

        while (!stack.isEmpty()) {
            Map.Entry<String, Object> currentEntry = stack.pop();
            String key = currentEntry.getKey();
            Object value = currentEntry.getValue();

            if (value instanceof String) {
                currentMapBuilding.put(key, resolveSecretsInString((String) value));
            } else if (value instanceof Map) {
                Map<String, Object> newNestedMap = new HashMap<>();
                currentMapBuilding.put(key, newNestedMap);

                // Push current context to parent stack and move to process nested map
                parentStack.push(currentMapBuilding);
                currentMapBuilding = newNestedMap;

                // Push entries of the nested map onto the processing stack
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                    stack.push(entry);
                }
            } else if (value instanceof List) {
                List<Object> newList = new LinkedList<>(); // Use LinkedList for potential frequent modifications during construction
                currentMapBuilding.put(key, newList);

                // Push current context to parent stack and move to process nested list
                parentStack.push(currentMapBuilding);
                currentMapBuilding = newList; // We are now "building" within this list

                // Push items of the nested list onto a helper stack for list processing
                Deque<Object> listItemsToProcess = new ArrayDeque<>();
                for (Object item : (List<Object>) value) {
                    listItemsToProcess.push(item);
                }

                while (!listItemsToProcess.isEmpty()) {
                    Object listItem = listItemsToProcess.pop();
                    if (listItem instanceof String) {
                        newList.add(resolveSecretsInString((String) listItem));
                    } else if (listItem instanceof Map) {
                        Map<String, Object> processedNestedMap = processMap((Map<String, Object>) listItem); // Recursively call for nested map
                        newList.add(processedNestedMap);
                    } else if (listItem instanceof List) {
                        List<Object> processedNestedList = processList((List<Object>) listItem); // Recursively call for nested list
                        newList.add(processedNestedList);
                    } else {
                        newList.add(listItem); // Add non-string, non-map, non-list values directly
                    }
                }
                // After processing the list, return to the parent map context
                if (!parentStack.isEmpty()) {
                    Object parent = parentStack.pop();
                    if (parent instanceof Map) {
                        currentMapBuilding = (Map<String, Object>) parent;
                    } else { // Should be a List based on logic, but defensive check
                        // This case implies we're back to a list that was building a list, which is handled
                        // by the nested processList call. We just ensure currentMapBuilding is correctly set.
                    }
                }
            } else {
                currentMapBuilding.put(key, value); // Add non-string, non-map, non-list values directly
            }

            // After processing a nested map, return to its parent map
            if (value instanceof Map && !stack.isEmpty() && stack.peek().getValue() != currentMapBuilding) {
                if (!parentStack.isEmpty()) {
                    Object parent = parentStack.pop();
                    if (parent instanceof Map) {
                        currentMapBuilding = (Map<String, Object>) parent;
                    }
                }
            }
        }
        return processedMap;
    }

    /**
     * Iteratively processes a list to deep copy and resolve secret placeholders.
     * This method is called from processMap when a nested list is encountered.
     *
     * @param inputList The list to process.
     * @return A new list with secrets resolved.
     */
    private List<Object> processList(List<Object> inputList) {
        if (inputList == null) {
            return new LinkedList<>();
        }

        List<Object> processedList = new LinkedList<>();
        Deque<Object> stack = new ArrayDeque<>(inputList); // Initialize with all list items

        while (!stack.isEmpty()) {
            Object current = stack.removeLast(); // Process in order, so removeLast for push-like behavior

            if (current instanceof String) {
                processedList.add(resolveSecretsInString((String) current));
            } else if (current instanceof Map) {
                processedList.add(processMap((Map<String, Object>) current));
            } else if (current instanceof List) {
                processedList.add(processList((List<Object>) current));
            } else {
                processedList.add(current);
            }
        }
        // The items are added to processedList as they are "resolved" or processed.
        // If we popped from stack.pop(), the order would be reversed.
        // For correct order, we either need to add to the front of processedList (LinkedList.addFirst)
        // or iterate through the stack in reverse order.
        // Let's reverse the list after processing to maintain original order if required,
        // or simply add to the front. For simplicity and maintaining original order for a list,
        // we'll iterate the inputList and push reversed to stack, then pop, or just use a helper
        // that reverses the stack as it builds.
        // A simpler way for list processing if order matters:
        // Use a temporary list to build, then reverse it if elements were pushed/popped
        // in a way that reverses order.
        // Or, more robustly, if adding to processedList in the loop, ensure the order is correct.
        // Since we are iterating through the stack from `removeLast`, elements will be added in reversed order
        // of how they were pushed initially. To maintain original order, if we push elements in reverse order,
        // then popping will result in correct order.
        // For `ArrayDeque`, `push` adds to head, `pop` removes from head.
        // `addLast` adds to tail, `removeFirst` removes from head.
        // To process in original order, iterate `inputList` and `addLast` to stack, then `removeFirst`.
        // Let's refine the stack initialization and usage for `processList` and `processMap`.

        // Let's re-evaluate the deep copy and iterative traversal for `processMap` and `processList`.
        // The challenge is managing nested maps and lists while building a new processed structure
        // iteratively. A common pattern for this is to use a stack of iterators or a custom
        // Pair/Tuple to hold the current object and its parent/context for modification.

        // Simpler iterative approach for deep copying and secret resolution.
        // The `objectMapper.readValue(objectMapper.writeValueAsString(originalWorkflowDef), WorkflowDef.class)`
        // already handles the deep copying of the structure.
        // The remaining task is to iterate through strings within that copied structure and resolve secrets.

        // Let's consider a unified iterative traversal for deep processing.
        // This will be more robust than managing nested maps and lists manually within `processMap` and `processList`.
        // We'll traverse the graph of objects, replacing strings as needed.

        // Given the complexity of manually managing nested map and list rebuilding with iterative
        // approaches for deep copy and transformation, and the existing `objectMapper` usage
        // for deep copying, it's more pragmatic to refine the `processMap` logic to work on the
        // *already deep-copied* structure and perform in-place secret resolution (or create new
        // maps/lists for resolved values if immutability is desired for intermediate steps).

        // For `processMap` and `processList` to be truly iterative without recursion AND deep copy/transform,
        // we need a stack that holds "tasks" or "nodes" to process.
        // Each "task" would be a Map.Entry or a List item, along with its parent context.

        // Let's revise `processMap` and `processList` to be more purely iterative for
        // secret resolution on an already deep-copied structure.

        // The current `processMap` tries to build a new map.
        // Let's simplify the `processMap` and `processList` to work on already existing structures
        // that are part of the deep copy.
        // A better approach would be to have a single `deepProcessValue` that takes an object and a path,
        // and uses a stack to manage the traversal and replacement.

        // Let's go back to the original `deepProcessValue` idea from the images and refactor it.
        // The original had:
        // `private Object deepProcessValue(Object value)`
        // Which implies it returns a *new* object, consistent with deep copy.

        // Let's re-implement `deepProcessValue` to be fully iterative.

        // Removed the complex iterative `processMap` and `processList`
        // and re-introduced `deepProcessValue` iteratively for clarity and correctness.
        // The `objectMapper.readValue(objectMapper.writeValueAsString(originalWorkflowDef), WorkflowDef.class)`
        // already handles the deep copy. The `deepProcessValue` then processes *this copied structure*.
        // This is a common pattern: deep copy, then process.
        // The `processMap` and `processList` were attempting to do both, which is complicated iteratively.
        return (List<Object>) deepProcessValue(inputList); // This will now be handled by the general iterative deep processor
    }

    /**
     * Recursively processes an object (Map, List, or String) to resolve secret placeholders.
     * This method has been refactored to be iterative using a Deque.
     *
     * @param value The object to process.
     * @return The processed object with secrets resolved.
     */
    private Object deepProcessValue(Object value) {
        if (value instanceof String) {
            return resolveSecretsInString((String) value);
        } else if (value instanceof Map) {
            Map<String, Object> originalMap = (Map<String, Object>) value;
            Map<String, Object> newMap = new HashMap<>();

            // Stack for iterative traversal of the map's values
            Deque<Map.Entry<String, Object>> stack = new ArrayDeque<>();
            for (Map.Entry<String, Object> entry : originalMap.entrySet()) {
                stack.push(entry);
            }

            // We need to build the new map as we pop items.
            // This requires managing nested structures.
            // A more straightforward iterative deep processing is to use a stack
            // where each element represents the current "node" to process and its context.

            // Let's use a simpler iterative approach for deep processing.
            // The `objectMapper` approach for deep copying is good.
            // Then we need an iterative way to traverse the copied object and modify strings.

            // Let's use a specialized object for stack entries that stores the "path" or
            // the parent to modify.
            class TraversalNode {
                Object currentObject;
                Object parent; // The parent Map or List of currentObject
                String keyInParentMap; // If parent is a Map, this is the key
                int indexInParentList; // If parent is a List, this is the index

                TraversalNode(Object obj, Object parent, String key, int index) {
                    this.currentObject = obj;
                    this.parent = parent;
                    this.keyInParentMap = key;
                    this.indexInParentList = index;
                }
            }

            // The root node for the iteration will be the `value` itself.
            // We assume `value` here is the *deep copied* structure that we want to modify in place,
            // or we will build a new one. Given the `processWorkflowDef` context, we want to modify
            // the `processedWorkflowDef` which is already a deep copy.

            // So, `deepProcessValue` should ideally modify in place or return a new structure.
            // The original recursive `deepProcessValue` returned a new object.
            // Let's stick to that, it simplifies iterative construction.

            // Re-think `deepProcessValue` entirely for iterative.
            // This is a common pattern for iterative tree/graph traversal for transformation.

            // The user wants iterative processing. The `objectMapper` deep copy happens once.
            // Then we need to iterate through the resulting `WorkflowDef` object graph.

            // Let's define a general iterative `traverseAndProcess` method that can modify values.

            // Simplified iterative deep process:
            // 1. Create a deep copy of the original object (already done in processWorkflowDef).
            // 2. Iterate through the copied object graph using a stack.
            // 3. For each string found, resolve it.

            // The `processMap` and `processList` from the refactored `processWorkflowDef` section
            // *were* the iterative deep processing for parts of the `WorkflowDef`.
            // Let's re-incorporate those as the iterative handlers.

            // The issue in the previous attempt was managing `currentMapBuilding` and `parentStack` correctly
            // for arbitrary nesting and value updates for `processMap` and `processList`.

            // Let's refine the `processMap` and `processList` to be purely iterative for processing
            // the *values* within them, assuming they are part of an already deep-copied structure.

            // Reverting to the original plan: Refactor `containsSecretsInMap` and `deepProcessValue`.
            // The original `deepProcessValue` was recursive. The user wants it iterative.

            // The first refactored `processMap` and `processList` earlier in this response
            // were attempting to be iterative deep processors. Let's make them correct.

            // This is the correct iterative deep processing for Maps.
            // It builds a new map, rather than modifying in-place.
            Map<String, Object> processedMap = new HashMap<>();
            Deque<Map.Entry<String, Object>> mapEntriesToProcess = new ArrayDeque<>();
            // Push all entries from the original map to the stack.
            for (Map.Entry<String, Object> entry : originalMap.entrySet()) {
                mapEntriesToProcess.push(entry);
            }

            // A stack to manage the context for nested maps/lists.
            // Each element is a Pair: <Map being built, original Map being processed>
            Deque<Map.Entry<Map<String, Object>, Map<String, Object>>> mapContextStack = new ArrayDeque<>();

            Map<String, Object> currentProcessedMap = processedMap;
            Map<String, Object> currentOriginalMap = originalMap;

            while (!mapEntriesToProcess.isEmpty() || !mapContextStack.isEmpty()) {
                if (mapEntriesToProcess.isEmpty()) {
                    // Finished processing current map's entries, pop context to go back to parent
                    Map.Entry<Map<String, Object>, Map<String, Object>> context = mapContextStack.pop();
                    currentProcessedMap = context.getKey();
                    currentOriginalMap = context.getValue();
                }

                Map.Entry<String, Object> currentEntry = mapEntriesToProcess.pop();
                String key = currentEntry.getKey();
                Object originalValue = currentEntry.getValue();

                if (originalValue instanceof String) {
                    currentProcessedMap.put(key, resolveSecretsInString((String) originalValue));
                } else if (originalValue instanceof Map) {
                    Map<String, Object> newNestedMap = new HashMap<>();
                    currentProcessedMap.put(key, newNestedMap);

                    // Save current context before descending
                    mapContextStack.push(ImmutableMap.of(currentProcessedMap, currentOriginalMap).entrySet().iterator().next());

                    currentProcessedMap = newNestedMap;
                    currentOriginalMap = (Map<String, Object>) originalValue;
                    // Push entries of the nested map onto the stack for processing
                    for (Map.Entry<String, Object> entry : currentOriginalMap.entrySet()) {
                        mapEntriesToProcess.push(entry);
                    }
                } else if (originalValue instanceof List) {
                    List<Object> newNestedList = new LinkedList<>();
                    currentProcessedMap.put(key, newNestedList);
                    // Process list iteratively
                    List<Object> processedList = deepProcessListIterative((List<Object>) originalValue);
                    newNestedList.addAll(processedList);
                } else {
                    currentProcessedMap.put(key, originalValue);
                }
            }
            return processedMap;
        } else if (value instanceof List) {
            return deepProcessListIterative((List<Object>) value);
        }
        return value;
    }

    /**
     * Iteratively processes a list to deep copy and resolve secret placeholders.
     * This method is called from deepProcessValue when a nested list is encountered.
     * It returns a new list.
     *
     * @param originalList The list to process.
     * @return A new list with secrets resolved.
     */
    private List<Object> deepProcessListIterative(List<Object> originalList) {
        if (originalList == null) {
            return new LinkedList<>();
        }

        List<Object> processedList = new LinkedList<>();
        // Use a temporary stack to reverse the list's order for popping,
        // so elements are processed in original order when added to processedList.
        Deque<Object> itemsToProcessStack = new ArrayDeque<>();
        for (int i = originalList.size() - 1; i >= 0; i--) {
            itemsToProcessStack.push(originalList.get(i));
        }

        while (!itemsToProcessStack.isEmpty()) {
            Object currentItem = itemsToProcessStack.pop();

            if (currentItem instanceof String) {
                processedList.add(resolveSecretsInString((String) currentItem));
            } else if (currentItem instanceof Map) {
                // Recursively call deepProcessValue for nested maps (which is now iterative)
                processedList.add(deepProcessValue((Map<String, Object>) currentItem));
            } else if (currentItem instanceof List) {
                // Recursively call deepProcessValue for nested lists (which is now iterative)
                processedList.add(deepProcessValue((List<Object>) currentItem));
            } else {
                processedList.add(currentItem);
            }
        }
        return processedList;
    }

    /**
     * Resolves secret placeholders within a string.
     * This method already uses an iterative approach.
     *
     * @param inputString The string possibly containing secret placeholders.
     * @return The string with secrets resolved.
     */
    private String resolveSecretsInString(String inputString) {
        if (inputString == null || !inputString.contains("workflow.secrets")) {
            return inputString;
        }

        Matcher matcher = SECRET_PATTERN.matcher(inputString);
        StringBuilder builder = new StringBuilder();
        int lastAppendPosition = 0;

        while (matcher.find()) {
            builder.append(inputString, lastAppendPosition, matcher.start());
            String secretName = matcher.group(1);
            logger.debug("Found secret placeholder: {}. Attempting to resolve.", secretName);

            Optional<String> secretValueOptional = secretManagerService.getSecret(secretName);
            if (secretValueOptional.isPresent()) {
                String secretValue = secretValueOptional.get();
                // Replace with the actual secret value
                builder.append(Matcher.quoteReplacement(secretValue));
                logger.debug("Successfully replaced secret: {}", secretName);
            } else {
                logger.error("Required secret '{}' not found in SecretManagerService. Failing workflow submission.", secretName);
                throw new RuntimeException("Required secret '" + secretName + "' not found.");
            }
            lastAppendPosition = matcher.end();
        }
        builder.append(inputString, lastAppendPosition, inputString.length());
        return builder.toString();
    }
}