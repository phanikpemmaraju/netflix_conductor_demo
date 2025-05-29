src/main/java/com/example/conductor/
        ├── dao/
        │   └── MyEncryptingExecutionDAO.java
├── security/
        │   ├── AesEncryptionService.java
│   ├── AesKmsClient.java
│   ├── EncryptionService.java
│   └── KmsClient.java
└── MyApplicationConfig.java
src/main/resources/
        └── application.properties


=======

<dependencies>
    <dependency>
        <groupId>com.netflix.conductor</groupId>
        <artifactId>conductor-server</artifactId>
        <version>3.x.y</version>
    </dependency>
    <dependency>
        <groupId>com.netflix.conductor</groupId>
        <artifactId>conductor-postgres</artifactId>
        <version>3.x.y</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-logging</artifactId>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
        <groupId>com.jayway.jsonpath</groupId>
        <artifactId>json-path</artifactId>
        <version>2.9.0</version> </dependency>
    <dependency>
        <groupId>commons-codec</groupId>
        <artifactId>commons-codec</artifactId>
        <version>1.16.1</version>
    </dependency>
</dependencies>

=========

// src/main/java/com/example/conductor/security/KmsClient.java
        package com.example.conductor.security;

import javax.crypto.SecretKey;

public interface KmsClient {
    /**
     * Retrieves an encryption/decryption key for a given client ID.
     * In a real scenario, this would involve secure communication with a KMS.
     * @param clientId The ID of the client for whom the key is needed.
     * @return A SecretKey for encryption/decryption.
     * @throws Exception if key retrieval fails.
     */
    SecretKey getEncryptionKey(String clientId) throws Exception;
}

========

// src/main/java/com/example/conductor/security/AesKmsClient.java
        package com.example.conductor.security;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DUMMY AES KMS Client for demonstration purposes.
 * WARNING: This implementation is NOT secure for production.
 * - Keys are generated in-memory and not securely stored/retrieved.
 * - No proper key rotation, access control, or secure key management.
 * - All client IDs will use the same fixed key in this simplified example.
 * In a real KMS, each clientId would map to a unique, securely managed key.
 */
public class AesKmsClient implements KmsClient {

    private static final Logger logger = LoggerFactory.getLogger(AesKmsClient.class);
    private final ConcurrentMap<String, SecretKey> clientKeys = new ConcurrentHashMap<>();
    private final SecretKey masterDemoKey; // Single fixed key for all clients in this demo

    public AesKmsClient() throws NoSuchAlgorithmException {
        // In a real KMS, this key would be securely retrieved, not generated like this.
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256); // 256-bit AES key
        this.masterDemoKey = keyGen.generateKey();
        logger.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        logger.warn("!!! WARNING: AesKmsClient is for DEMO ONLY. NOT SECURE! !!!");
        logger.warn("!!! DO NOT USE IN PRODUCTION.                         !!! ");
        logger.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
    }

    @Override
    public SecretKey getEncryptionKey(String clientId) throws Exception {
        // In a real KMS, you'd fetch the specific key for 'clientId' from the KMS.
        // For this demo, all clients get the same master demo key.
        logger.debug("Retrieving demo key for clientId: {}", clientId);
        clientKeys.putIfAbsent(clientId, masterDemoKey); // Store for simplicity in demo
        return clientKeys.get(clientId);
    }
}

========


// src/main/java/com/example/conductor/security/EncryptionService.java
        package com.example.conductor.security;

public interface EncryptionService {
    /**
     * Encrypts a plaintext string.
     * @param plaintext The string to encrypt.
     * @param clientId The ID of the client whose key should be used.
     * @return The base64-encoded ciphertext.
     * @throws Exception if encryption fails.
     */
    String encrypt(String plaintext, String clientId) throws Exception;

    /**
     * Decrypts a base64-encoded ciphertext.
     * @param ciphertext The base64-encoded ciphertext.
     * @param clientId The ID of the client whose key should be used.
     * @return The decrypted plaintext.
     * @throws Exception if decryption fails.
     */
    String decrypt(String ciphertext, String clientId) throws Exception;
}


==========


// src/main/java/com/example/conductor/security/AesEncryptionService.java
        package com.example.conductor.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DUMMY AES Encryption Service for demonstration purposes.
 * WARNING: This implementation is NOT secure for production.
 * - Uses a fixed IV, which makes it vulnerable to attacks (e.g., replay, known-plaintext).
 * In production, a unique, cryptographically random IV must be generated for each encryption
 * operation and securely stored with the ciphertext (e.g., prepended to it).
 * - No proper error handling for cryptographic operations.
 * - No key rotation logic.
 */
public class AesEncryptionService implements EncryptionService {

    private static final Logger logger = LoggerFactory.getLogger(AesEncryptionService.class);
    // DUMMY IV: In production, generate a unique IV per encryption and prepend it to ciphertext
    private static final byte[] DUMMY_IV = "ThisIsADummyIV12".getBytes(StandardCharsets.UTF_8); // 16 bytes for AES

    private final KmsClient kmsClient;

    public AesEncryptionService(KmsClient kmsClient) {
        this.kmsClient = kmsClient;
        logger.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        logger.warn("!!! WARNING: AesEncryptionService is for DEMO ONLY. NOT SECURE!  !!!");
        logger.warn("!!! Uses DUMMY_IV. DO NOT USE IN PRODUCTION.                     !!!");
        logger.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
    }

    @Override
    public String encrypt(String plaintext, String clientId) throws Exception {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        SecretKey secretKey = kmsClient.getEncryptionKey(clientId);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); // CBC mode requires IV
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(DUMMY_IV));
        byte[] encryptedBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    @Override
    public String decrypt(String ciphertext, String clientId) throws Exception {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        SecretKey secretKey = kmsClient.getEncryptionKey(clientId);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); // CBC mode requires IV
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(DUMMY_IV));
        byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }
}


======


// src/main/java/com/example/conductor/dao/MyEncryptingExecutionDAO.java
        package com.example.conductor.dao;

import com.example.conductor.security.EncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.common.metadata.tasks.TaskModel;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.dao.ExecutionDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A decorating ExecutionDAO that encrypts/decrypts sensitive PII fields
 * within workflow/task inputs/outputs.
 *
 * All encryption configuration (clientId, sensitive paths, enablement) is now
 * dynamically resolved from the Workflow and TaskDef objects.
 */
public class MyEncryptingExecutionDAO implements ExecutionDAO {

    private static final Logger logger = LoggerFactory.getLogger(MyEncryptingExecutionDAO.class);
    private static final String ENCRYPTED_PREFIX = "ENC:";
    private static final Configuration JSON_PATH_CONF = Configuration.defaultConfiguration().addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL);

    // Default Client ID to use if 'clientId' is not specified anywhere in Workflow/Task context
    private static final String DEFAULT_CLIENT_ID = "GLOBAL_DEFAULT_CLIENT";

    // Keys for custom metadata in TaskDef and WorkflowDef inputTemplate maps
    private static final String SENSITIVE_PATHS_KEY = "_sensitivePaths"; // For both WorkflowDef and TaskDef
    private static final String CLIENT_ID_KEY = "_clientId";             // For TaskDef (override workflow-level)
    private static final String ENABLE_ENCRYPTION_KEY = "_enableEncryption"; // For Workflow/Task input (override default)
    private static final String DEFAULT_ENCRYPTION_ENABLED_KEY = "_defaultEncryptionEnabled"; // For WorkflowDef

    private final ExecutionDAO delegate;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    // PiiPathsConfig is no longer a dependency for this DAO.
    // Its functionality is now absorbed into dynamic lookup in Workflow/TaskDefs.
    // private final PiiPathsConfig piiPathsConfig;

    public MyEncryptingExecutionDAO(
            ExecutionDAO delegate,
            EncryptionService encryptionService,
            ObjectMapper objectMapper) { // PiiPathsConfig removed from constructor
        this.delegate = delegate;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
        logger.info("MyEncryptingExecutionDAO initialized, wrapping {}. All encryption configuration is now dynamic.", delegate.getClass().getSimpleName());
    }

    // --- Helper Methods for Client ID and Encryption Status/Paths ---

    /**
     * Resolves the Client ID for a given payload's encryption.
     * Priority: TaskModel.inputData.clientId > TaskDef.inputTemplate._clientId >
     * Workflow.input.clientId > Workflow.variables.clientId > DEFAULT_CLIENT_ID.
     * @param workflow The workflow instance context.
     * @param task The task model (can be null if resolving for a workflow-level payload).
     * @return The resolved clientId string.
     */
    private String resolveClientId(Workflow workflow, TaskModel task) {
        // 1. Check TaskModel's inputData directly for a "clientId" field
        if (task != null && task.getInputData() != null && task.getInputData().containsKey("clientId")) {
            Object clientId = task.getInputData().get("clientId");
            if (clientId instanceof String) {
                logger.debug("Resolved client ID '{}' from task input for task {}", clientId, task.getTaskId());
                return (String) clientId;
            }
        }

        // 2. Check TaskDef's inputTemplate for a "_clientId" field
        if (workflow != null && workflow.getWorkflowDefinition() != null && task != null) {
            TaskDef taskDef = workflow.getWorkflowDefinition().getTaskByRefName(task.getReferenceTaskName());
            if (taskDef != null && taskDef.getInputTemplate() != null && taskDef.getInputTemplate().containsKey(CLIENT_ID_KEY)) {
                Object clientId = taskDef.getInputTemplate().get(CLIENT_ID_KEY);
                if (clientId instanceof String) {
                    logger.debug("Resolved client ID '{}' from task definition {} for task {}", clientId, taskDef.getName(), task.getTaskId());
                    return (String) clientId;
                }
            }
        }

        // 3. Fallback to Workflow's input for a "clientId" field
        if (workflow != null && workflow.getInput() != null && workflow.getInput().containsKey("clientId")) {
            Object clientId = workflow.getInput().get("clientId");
            if (clientId instanceof String) {
                logger.debug("Resolved client ID '{}' from workflow input for workflow {}", clientId, workflow.getWorkflowId());
                return (String) clientId;
            }
        }

        // 4. Fallback to Workflow's variables for a "clientId" field
        if (workflow != null && workflow.getVariables() != null && workflow.getVariables().containsKey("clientId")) {
            Object clientId = workflow.getVariables().get("clientId");
            if (clientId instanceof String) {
                logger.debug("Resolved client ID '{}' from workflow variables for workflow {}", clientId, workflow.getWorkflowId());
                return (String) clientId;
            }
        }

        // 5. Final fallback to default client ID
        logger.debug("Client ID not found for workflow {}/task {}. Using DEFAULT_CLIENT_ID: {}",
                workflow != null ? workflow.getWorkflowId() : "N/A",
                task != null ? task.getTaskId() : "N/A",
                DEFAULT_CLIENT_ID);
        return DEFAULT_CLIENT_ID;
    }

    /**
     * Determines if encryption should be enabled for a given payload context.
     * Priority: Task-level _enableEncryption > Workflow-level _enableEncryption > WorkflowDef._defaultEncryptionEnabled (if set).
     * @param workflow The workflow context.
     * @param task The task model (can be null if processing workflow payload).
     * @return true if encryption is enabled.
     */
    private boolean isEncryptionEnabledForContext(Workflow workflow, TaskModel task) {
        // 1. Check TaskModel's inputData for explicit '_enableEncryption' flag (task-specific override)
        if (task != null && task.getInputData() != null && task.getInputData().containsKey(ENABLE_ENCRYPTION_KEY)) {
            Object enabled = task.getInputData().get(ENABLE_ENCRYPTION_KEY);
            if (enabled instanceof Boolean) {
                logger.debug("Encryption enabled status for task {} resolved from task input: {}", task.getTaskId(), enabled);
                return (Boolean) enabled;
            }
        }

        // 2. Check Workflow's input for explicit '_enableEncryption' flag (workflow-level override)
        if (workflow != null && workflow.getInput() != null && workflow.getInput().containsKey(ENABLE_ENCRYPTION_KEY)) {
            Object enabled = workflow.getInput().get(ENABLE_ENCRYPTION_KEY);
            if (enabled instanceof Boolean) {
                logger.debug("Encryption enabled status for workflow {} resolved from workflow input: {}", workflow.getWorkflowId(), enabled);
                return (Boolean) enabled;
            }
        }

        // 3. Fallback to WorkflowDef's _defaultEncryptionEnabled flag (blueprint-level default)
        if (workflow != null && workflow.getWorkflowDefinition() != null && workflow.getWorkflowDefinition().getInputTemplate() != null
                && workflow.getWorkflowDefinition().getInputTemplate().containsKey(DEFAULT_ENCRYPTION_ENABLED_KEY)) {
            Object defaultEnabled = workflow.getWorkflowDefinition().getInputTemplate().get(DEFAULT_ENCRYPTION_ENABLED_KEY);
            if (defaultEnabled instanceof Boolean) {
                logger.debug("Encryption enabled status for workflow {} resolved from WorkflowDef default: {}", workflow.getWorkflowId(), defaultEnabled);
                return (Boolean) defaultEnabled;
            }
        }

        // 4. Default to false if no explicit enablement found anywhere.
        logger.debug("Encryption enabled status not explicitly set for workflow {}/task {}. Defaulting to false.",
                workflow != null ? workflow.getWorkflowId() : "N/A",
                task != null ? task.getTaskId() : "N/A");
        return false;
    }

    /**
     * Retrieves the set of sensitive JSON paths for a given payload.
     * Priority: TaskDef.inputTemplate._sensitivePaths > WorkflowDef.inputTemplate._sensitivePaths.
     *
     * @param workflow The workflow instance (to get WorkflowDef and TaskDef).
     * @param task The task model (if processing task data).
     * @return A Set of JSON paths to encrypt/decrypt.
     */
    private Set<String> getSensitivePathsForPayload(Workflow workflow, TaskModel task) {
        if (workflow == null || workflow.getWorkflowDefinition() == null) {
            logger.warn("Cannot determine sensitive paths: Workflow or Workflow Definition is null.");
            return Collections.emptySet();
        }

        Set<String> paths = new java.util.HashSet<>();

        // 1. If processing a task's payload, look for sensitive paths in its TaskDef's inputTemplate
        if (task != null) {
            TaskDef taskDef = workflow.getWorkflowDefinition().getTaskByRefName(task.getReferenceTaskName());
            if (taskDef != null && taskDef.getInputTemplate() != null && taskDef.getInputTemplate().containsKey(SENSITIVE_PATHS_KEY)) {
                Object sensitivePathsObj = taskDef.getInputTemplate().get(SENSITIVE_PATHS_KEY);
                if (sensitivePathsObj instanceof List) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<String> rawPaths = (List<String>) sensitivePathsObj;
                        paths.addAll(rawPaths.stream()
                                .filter(String.class::isInstance)
                                .map(String.class::cast)
                                .collect(Collectors.toSet()));
                        logger.debug("Resolved sensitive paths {} from TaskDef {} for task {}", paths, taskDef.getName(), task.getTaskId());
                        return paths; // Return immediately, task-specific paths are highest priority
                    } catch (ClassCastException e) {
                        logger.error("Value for '{}' in TaskDef '{}' inputTemplate is not a List<String>. Error: {}",
                                SENSITIVE_PATHS_KEY, taskDef.getName(), e.getMessage());
                    }
                } else {
                    logger.warn("Value for '{}' in TaskDef '{}' inputTemplate is not a List. Type: {}",
                            SENSITIVE_PATHS_KEY, taskDef.getName(), sensitivePathsObj != null ? sensitivePathsObj.getClass().getName() : "null");
                }
            } else {
                logger.debug("TaskDef '{}' does not have '{}' defined in its inputTemplate.", taskDef != null ? taskDef.getName() : "N/A", SENSITIVE_PATHS_KEY);
            }
        }

        // 2. Fallback: Check WorkflowDef's inputTemplate for "_sensitivePaths" (for workflow-level sensitive data or tasks without specific TaskDef paths)
        if (workflow.getWorkflowDefinition().getInputTemplate() != null && workflow.getWorkflowDefinition().getInputTemplate().containsKey(SENSITIVE_PATHS_KEY)) {
            Object sensitivePathsObj = workflow.getWorkflowDefinition().getInputTemplate().get(SENSITIVE_PATHS_KEY);
            if (sensitivePathsObj instanceof List) {
                try {
                    @SuppressWarnings("unchecked")
                    List<String> rawPaths = (List<String>) sensitivePathsObj;
                    paths.addAll(rawPaths.stream()
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .collect(Collectors.toSet()));
                    logger.debug("Resolved sensitive paths {} from WorkflowDef for workflow {}", paths, workflow.getWorkflowId());
                    return paths;
                } catch (ClassCastException e) {
                    logger.error("Value for '{}' in WorkflowDef inputTemplate is not a List<String>. Error: {}",
                            SENSITIVE_PATHS_KEY, e.getMessage());
                }
            } else {
                logger.warn("Value for '{}' in WorkflowDef inputTemplate is not a List. Type: {}",
                        SENSITIVE_PATHS_KEY, sensitivePathsObj != null ? sensitivePathsObj.getClass().getName() : "null");
            }
        }

        logger.debug("No specific sensitive paths found for payload for workflow {} / task {}.",
                workflow.getWorkflowId(), task != null ? task.getTaskId() : "N/A");
        return Collections.emptySet();
    }


    /**
     * Generic method to process a JSON payload (encryption or decryption).
     *
     * @param payload The Map<String, Object> representing the JSON data.
     * @param workflowContext The workflow instance context.
     * @param task The task model (can be null if processing workflow payload).
     * @param encrypt True to encrypt, false to decrypt.
     * @return The processed payload map.
     */
    private Map<String, Object> processPayload(Map<String, Object> payload, Workflow workflowContext, TaskModel task, boolean encrypt) {
        // First, check if encryption is enabled at all for this context.
        if (payload == null || payload.isEmpty() || !isEncryptionEnabledForContext(workflowContext, task)) {
            return payload; // No payload, or encryption not enabled for this workflow/task context.
        }

        // Get the client ID specific to this encryption/decryption operation.
        String resolvedClientId = resolveClientId(workflowContext, task);

        // Get the sensitive paths specific to this payload (workflow or task).
        Set<String> sensitivePaths = getSensitivePathsForPayload(workflowContext, task);
        if (sensitivePaths.isEmpty()) {
            logger.debug("No sensitive paths defined for payload for workflow {} / task {}. Skipping processing.",
                    workflowContext != null ? workflowContext.getWorkflowId() : "N/A",
                    task != null ? task.getTaskId() : "N/A");
            return payload; // No sensitive paths defined, no encryption/decryption needed.
        }

        try {
            // Convert Map to JSON string, then use JsonPath for robust processing
            String jsonPayload = objectMapper.writeValueAsString(payload);
            DocumentContext documentContext = JsonPath.using(JSON_PATH_CONF).parse(jsonPayload);

            for (String path : sensitivePaths) {
                try {
                    Object value = documentContext.read(path);
                    if (value instanceof String) {
                        String stringValue = (String) value;
                        if (encrypt) {
                            if (!stringValue.startsWith(ENCRYPTED_PREFIX)) { // Avoid double encryption
                                String encryptedValue = encryptionService.encrypt(stringValue, resolvedClientId);
                                documentContext.set(path, ENCRYPTED_PREFIX + encryptedValue);
                                logger.debug("Encrypted path: {} for client: {} in workflow {} / task {}", path, resolvedClientId, workflowContext.getWorkflowId(), task != null ? task.getTaskId() : "N/A");
                            } else {
                                logger.trace("Path {} for client {} in workflow {} / task {} already encrypted. Skipping.", path, resolvedClientId, workflowContext.getWorkflowId(), task != null ? task.getTaskId() : "N/A");
                            }
                        } else { // Decrypt
                            if (stringValue.startsWith(ENCRYPTED_PREFIX)) {
                                String ciphertext = stringValue.substring(ENCRYPTED_PREFIX.length());
                                String decryptedValue = encryptionService.decrypt(ciphertext, resolvedClientId);
                                documentContext.set(path, decryptedValue);
                                logger.debug("Decrypted path: {} for client: {} in workflow {} / task {}", path, resolvedClientId, workflowContext.getWorkflowId(), task != null ? task.getTaskId() : "N/A");
                            } else {
                                logger.trace("Path {} for client {} in workflow {} / task {} not encrypted. Skipping decryption.", path, resolvedClientId, workflowContext.getWorkflowId(), task != null ? task.getTaskId() : "N/A");
                            }
                        }
                    } else if (value != null) {
                        logger.warn("Value at path '{}' for client '{}' in workflow {} / task {} is not a String. Skipping encryption/decryption. Value type: {}", path, resolvedClientId, workflowContext.getWorkflowId(), task != null ? task.getTaskId() : "N/A", value.getClass().getName());
                    }
                } catch (Exception pathReadError) {
                    logger.debug("Path '{}' not found or could not be processed in payload for workflow {} / task {} / client {}. Skipping. Error: {}",
                            path, workflowContext.getWorkflowId(), task != null ? task.getTaskId() : "N/A", resolvedClientId, pathReadError.getMessage());
                }
            }
            return objectMapper.readValue(documentContext.jsonString(), Map.class);

        } catch (InvalidJsonException e) {
            logger.error("Invalid JSON payload encountered for encryption/decryption for workflow {} / task {} / client {}. Payload: {}",
                    workflowContext.getWorkflowId(), task != null ? task.getTaskId() : "N/A", resolvedClientId, payload, e);
            return payload;
        } catch (Exception e) {
            logger.error("Error during {} operation for workflow {} / task {} / client {}. Returning original payload to prevent data loss. Error: {}",
                    encrypt ? "encryption" : "decryption", workflowContext.getWorkflowId(), task != null ? task.getTaskId() : "N/A", resolvedClientId, e.getMessage(), e);
            return payload;
        }
    }

    // --- Workflow Operations ---

    @Override
    public String createWorkflow(Workflow workflow) {
        workflow.setInput(processPayload(workflow.getInput(), workflow, null, true));
        return delegate.createWorkflow(workflow);
    }

    @Override
    public void updateWorkflow(Workflow workflow) {
        if (workflow.getOutput() != null && !workflow.getOutput().isEmpty()) {
            workflow.setOutput(processPayload(workflow.getOutput(), workflow, null, true));
        }
        delegate.updateWorkflow(workflow);
    }

    @Override
    public boolean removeWorkflow(String workflowId) {
        return delegate.removeWorkflow(workflowId);
    }

    @Override
    public Workflow getWorkflow(String workflowId, boolean includeTasks) {
        Workflow workflow = delegate.getWorkflow(workflowId, includeTasks);
        if (workflow != null) {
            workflow.setInput(processPayload(workflow.getInput(), workflow, null, false));
            workflow.setOutput(processPayload(workflow.getOutput(), workflow, null, false));
            if (includeTasks && workflow.getTasks() != null) {
                for (TaskModel task : workflow.getTasks()) {
                    task.setInputData(processPayload(task.getInputData(), workflow, task, false));
                    task.setOutputData(processPayload(task.getOutputData(), workflow, task, false));
                }
            }
        }
        return workflow;
    }

    @Override
    public List<String> getWorkflowIds(String workflowName, String correlationId, boolean includeClosed, boolean includeTasks) {
        return delegate.getWorkflowIds(workflowName, correlationId, includeClosed, includeTasks);
    }

    @Override
    public List<String> getRunningWorkflowIds(String workflowName, int count) {
        return delegate.getRunningWorkflowIds(workflowName, count);
    }

    @Override
    public List<Workflow> getPendingWorkflows(String workflowName, long startTime, String lastExclusiveId) {
        List<Workflow> workflows = delegate.getPendingWorkflows(workflowName, startTime, lastExclusiveId);
        for(Workflow workflow : workflows){
            if (workflow != null) {
                workflow.setInput(processPayload(workflow.getInput(), workflow, null, false));
                workflow.setOutput(processPayload(workflow.getOutput(), workflow, null, false));
            }
        }
        return workflows;
    }

    @Override
    public long getRunningWorkflowCount(String workflowName) {
        return delegate.getRunningWorkflowCount(workflowName);
    }

    // --- Task Operations ---

    @Override
    public void createTask(TaskModel task) {
        Workflow workflow = delegate.getWorkflow(task.getWorkflowId(), false); // Fetch workflow without tasks
        if (workflow != null) {
            task.setInputData(processPayload(task.getInputData(), workflow, task, true));
            task.setOutputData(processPayload(task.getOutputData(), workflow, task, true));
        } else {
            logger.warn("Workflow with ID {} not found for task {}. Cannot determine encryption context. Skipping encryption for task data.", task.getWorkflowId(), task.getTaskId());
        }
        delegate.createTask(task);
    }

    @Override
    public void updateTask(TaskModel task) {
        Workflow workflow = delegate.getWorkflow(task.getWorkflowId(), false);
        if (workflow != null) {
            task.setOutputData(processPayload(task.getOutputData(), workflow, task, true));
        } else {
            logger.warn("Workflow with ID {} not found for task {}. Cannot determine encryption context for task update. Skipping encryption for task data.", task.getWorkflowId(), task.getTaskId());
        }
        delegate.updateTask(task);
    }

    @Override
    public boolean removeTask(String taskId) {
        return delegate.removeTask(taskId);
    }

    @Override
    public TaskModel getTask(String taskId) {
        TaskModel task = delegate.getTask(taskId);
        if (task != null) {
            Workflow workflow = delegate.getWorkflow(task.getWorkflowId(), false);
            if (workflow != null) {
                task.setInputData(processPayload(task.getInputData(), workflow, task, false));
                task.setOutputData(processPayload(task.getOutputData(), workflow, task, false));
            } else {
                logger.warn("Workflow with ID {} not found for task {}. Cannot decrypt task data. Returning raw data (potentially encrypted).", task.getWorkflowId(), task.getTaskId());
            }
        }
        return task;
    }

    @Override
    public List<TaskModel> getTasks(List<String> taskIds) {
        List<TaskModel> tasks = delegate.getTasks(taskIds);
        Map<String, Workflow> workflowCache = new HashMap<>();

        tasks.stream()
                .map(TaskModel::getWorkflowId)
                .distinct()
                .forEach(wfId -> workflowCache.computeIfAbsent(wfId, id -> delegate.getWorkflow(id, false)));

        for (TaskModel task : tasks) {
            if (task != null) {
                Workflow workflow = workflowCache.get(task.getWorkflowId());
                if (workflow != null) {
                    task.setInputData(processPayload(task.getInputData(), workflow, task, false));
                    task.setOutputData(processPayload(task.getOutputData(), workflow, task, false));
                } else {
                    logger.warn("Workflow with ID {} not found for task {}. Cannot decrypt task data in bulk get. Returning raw data.", task.getWorkflowId(), task.getTaskId());
                }
            }
        }
        return tasks;
    }

    @Override
    public List<TaskModel> getQueuedTasksForType(String taskType) {
        List<TaskModel> tasks = delegate.getQueuedTasksForType(taskType);
        Map<String, Workflow> workflowCache = new HashMap<>();
        tasks.stream()
                .map(TaskModel::getWorkflowId)
                .distinct()
                .forEach(wfId -> workflowCache.computeIfAbsent(wfId, id -> delegate.getWorkflow(id, false)));

        for (TaskModel task : tasks) {
            if (task != null) {
                Workflow workflow = workflowCache.get(task.getWorkflowId());
                if (workflow != null) {
                    task.setInputData(processPayload(task.getInputData(), workflow, task, false));
                    task.setOutputData(processPayload(task.getOutputData(), workflow, task, false));
                } else {
                    logger.warn("Workflow with ID {} not found for queued task {}. Cannot decrypt task data. Returning raw data.", task.getWorkflowId(), task.getTaskId());
                }
            }
        }
        return tasks;
    }

    @Override
    public long getTaskCount(String taskType) {
        return delegate.getTaskCount(taskType);
    }

    @Override
    public void bulkCreateTasks(List<TaskModel> tasks) {
        Map<String, Workflow> workflowCache = new HashMap<>();
        Set<String> uniqueWorkflowIds = tasks.stream().map(TaskModel::getWorkflowId).collect(Collectors.toSet());
        uniqueWorkflowIds.forEach(id -> workflowCache.put(id, delegate.getWorkflow(id, false)));

        for (TaskModel task : tasks) {
            Workflow workflow = workflowCache.get(task.getWorkflowId());
            if (workflow != null) {
                task.setInputData(processPayload(task.getInputData(), workflow, task, true));
                task.setOutputData(processPayload(task.getOutputData(), workflow, task, true));
            } else {
                logger.warn("Workflow with ID {} not found for task {}. Cannot determine encryption context for bulk create. Skipping encryption for task data.", task.getWorkflowId(), task.getTaskId());
            }
        }
        delegate.bulkCreateTasks(tasks);
    }

    @Override
    public List<Workflow> getWorkflows(String workflowName, Long createTimeStart, Long createTimeEnd) {
        List<Workflow> workflows = delegate.getWorkflows(workflowName, createTimeStart, createTimeEnd);
        for(Workflow workflow : workflows){
            if (workflow != null) {
                workflow.setInput(processPayload(workflow.getInput(), workflow, null, false));
                workflow.setOutput(processPayload(workflow.getOutput(), workflow, null, false));
                if (workflow.getTasks() != null) {
                    for (TaskModel task : workflow.getTasks()) {
                        task.setInputData(processPayload(task.getInputData(), workflow, task, false));
                        task.setOutputData(processPayload(task.getOutputData(), workflow, task, false));
                    }
                }
            }
        }
        return workflows;
    }

    // --- Workflow Definition Operations (no encryption needed here, as it's blueprint metadata) ---

    @Override
    public void createWorkflowDef(WorkflowDef def) {
        delegate.createWorkflowDef(def);
    }

    @Override
    public void updateWorkflowDef(WorkflowDef def) {
        delegate.updateWorkflowDef(def);
    }

    @Override
    public WorkflowDef getWorkflowDef(String name, int version) {
        return delegate.getWorkflowDef(name, version);
    }

    @Override
    public void removeWorkflowDef(String name, int version) {
        delegate.removeWorkflowDef(name, version);
    }

    @Override
    public List<WorkflowDef> getAllWorkflowDefs() {
        return delegate.getAllWorkflowDefs();
    }

    @Override
    public List<WorkflowDef> getAllLatestVersions() {
        return delegate.getAllLatestVersions();
    }

    @Override
    public void createOrUpdateWorkflowDef(WorkflowDef def) {
        delegate.createOrUpdateWorkflowDef(def);
    }
}


=========

// src/main/java/com/example/conductor/MyApplicationConfig.java
        package com.example.conductor;

import com.example.conductor.dao.MyEncryptingExecutionDAO;
import com.example.conductor.security.AesEncryptionService;
import com.example.conductor.security.AesKmsClient;
import com.example.conductor.security.EncryptionService;
import com.example.conductor.security.KmsClient;
// PiiPathsConfig is no longer imported as it's removed.
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.dao.ExecutionDAO;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.security.NoSuchAlgorithmException;

@Configuration
public class MyApplicationConfig {

    @Bean
    public KmsClient kmsClient() throws NoSuchAlgorithmException {
        return new AesKmsClient(); // DUMMY: Replace with your production-grade KMS client
    }

    @Bean
    public EncryptionService encryptionService(KmsClient kmsClient) {
        return new AesEncryptionService(kmsClient); // DUMMY: Replace with your production-grade encryption service
    }

    /**
     * This bean provides the primary ExecutionDAO for Conductor.
     * It wraps the underlying ExecutionDAO (e.g., PostgresExecutionDAO, RedisExecutionDAO)
     * that Conductor's auto-configuration provides based on 'conductor.db.type'.
     *
     * @param delegate The underlying ExecutionDAO provided by Conductor's persistence module.
     * Spring automatically injects the non-primary ExecutionDAO here.
     * @param encryptionService The encryption service for PII.
     * @param objectMapper The ObjectMapper for JSON serialization/deserialization.
     * @return An instance of MyEncryptingExecutionDAO, which transparently handles encryption/decryption.
     */
    @Bean
    @Primary // This tells Spring to prefer this ExecutionDAO bean when multiple are available.
    public ExecutionDAO encryptingExecutionDAO(
            ExecutionDAO delegate,
            EncryptionService encryptionService,
            ObjectMapper objectMapper) { // PiiPathsConfig removed from constructor
        return new MyEncryptingExecutionDAO(delegate, encryptionService, objectMapper);
    }
}

==========


        # src/main/resources/application.properties

# Conductor Database Type
conductor.db.type=postgres

# Conductor Postgres configuration (replace with your actual DB settings)
conductor.postgres.host=localhost
conductor.postgres.port=5432
conductor.postgres.user=conductor
conductor.postgres.password=password
conductor.postgres.db=conductor
conductor.postgres.readOnlyUser=conductor_read
conductor.postgres.adminUser=conductor_admin
conductor.postgres.pool.minimumIdle=2
conductor.postgres.pool.maximumPoolSize=10
conductor.postgres.schema.version=V1_0
conductor.postgres.flyway.enabled=true


=========


        {
        "name": "MyFullyDynamicSecureWorkflow",
        "version": 1,
        "description": "Workflow with dynamic PII encryption driven by definitions.",
        "inputParameters": ["workflowInput"],
        "outputParameters": {},
        "inputTemplate": {
        "_defaultEncryptionEnabled": true, // Default encryption enablement for this workflow. Can be overridden per task or at workflow instance start.
        "_sensitivePaths": [               // Optional: Sensitive paths for workflow's overall input/output if needed
        "$.workflowMetadata.sensitiveId",
        "$.workflowConfig.sensitiveToken"
        ]
        },
        "tasks": [
        {
        "name": "data_collection_task",
        "taskReferenceName": "collect_customer_data",
        "inputParameters": {
        "userId": "${workflow.input.userId}",
        "initialData": "${workflow.input.initialData}"
        },
        "inputTemplate": {
        "_clientId": "clientA", // Task-specific client ID for encryption. Overrides workflow's clientId.
        "_sensitivePaths": [    // Task-specific sensitive paths for its input/output
        "$.customerInfo.ssn",
        "$.customerInfo.dateOfBirth",
        "$.accountDetails.cardNumber"
        ],
        "_enableEncryption": true // Optional: Override default enablement for this specific task
        }
        },
        {
        "name": "identity_verification_task",
        "taskReferenceName": "verify_identity",
        "inputParameters": {
        "verificationData": "${collect_customer_data.output.verifiedInfo}"
        },
        "inputTemplate": {
        "_clientId": "clientB", // Different task, different client ID for encryption
        "_sensitivePaths": [
        "$.verificationData.documentId",
        "$.verificationData.biometricHash"
        ],
        "_enableEncryption": false // Optional: Explicitly disable encryption for this specific task
        }
        },
        {
        "name": "non_sensitive_task",
        "taskReferenceName": "process_public_data",
        "inputParameters": {
        "publicInfo": "${workflow.input.publicData}"
        },
        "inputTemplate": {
        // No _clientId or _sensitivePaths here, will use workflow default encryption enablement and no paths
        // Or could explicitly set "_enableEncryption": false
        }
        }
        ]
        }

        
