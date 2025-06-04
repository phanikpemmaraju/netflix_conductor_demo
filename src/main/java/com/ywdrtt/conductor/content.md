Secret Management in Conductor Workflows
Overview
This document outlines the design and implementation for integrating a secret management mechanism directly into Conductor workflows. This enhancement allows sensitive information, such as API keys, database credentials, or other secrets, to be securely injected into workflow inputs at runtime, rather than being hardcoded or passed in plain text. Secrets are stored in a database (e.g., PostgreSQL) and retrieved dynamically when a workflow or task requires them.

Motivation
Traditionally, passing sensitive data into workflows poses security challenges. Hardcoding secrets in workflow definitions or passing them directly in workflow input payloads is insecure and non-compliant with best security practices. This implementation aims to:

Enhance Security: Prevent hardcoding of sensitive information in workflow definitions.
Centralize Secret Management: Provide a unified location (database) for storing and managing secrets.
Improve Auditability: Allow for tracking of secret access and usage within the system.
Facilitate Rotation: Simplify secret rotation without requiring workflow definition changes.
Solution Architecture
The secret management solution introduces new components and modifies existing workflow execution flows to intercept and process workflow inputs for secret patterns.

Code snippet

@startuml
!theme mars

actor User as user
participant "Conductor API" as api
participant "NewWorkflowExecutorOps\n(Secret-Aware)" as newExecutor
participant "WorkflowPreProcessor" as preProcessor
participant "SecretManagerService" as secretService
participant "SecretManagerDAO" as secretDao
database "PostgreSQL Database\n(Secrets Table)" as db
participant "Original WorkflowExecutorOps\n(Wrapped/Default)" as originalExecutor

user -> api: Start Workflow (WorkflowDef with "$workflow.secrets.my_secret")
activate api

alt if conductor.secrets.enabled=true
api -> newExecutor: startWorkflow(input)
activate newExecutor
newExecutor -> preProcessor: processWorkflowInput(input)
activate preProcessor
preProcessor -> secretService: getSecret("my_secret")
activate secretService
secretService -> secretDao: getSecret("my_secret")
activate secretDao
secretDao -> db: SELECT value FROM secrets WHERE name = 'my_secret'
activate db
db --> secretDao: Encrypted Secret Value
deactivate db
secretDao --> secretService: Encrypted Secret Value
deactivate secretDao
secretService --> preProcessor: Decrypted Secret Value
deactivate secretService
preProcessor --> newExecutor: Processed Input (Secrets Replaced)
deactivate preProcessor
newExecutor -> originalExecutor: startWorkflow(processedInput)
deactivate newExecutor
else else if conductor.secrets.enabled=false (or missing)
api -> originalExecutor: startWorkflow(input)
end

activate originalExecutor
originalExecutor --> api: workflowId
deactivate originalExecutor
api --> user: workflowId
deactivate api

note right of newExecutor: Similar flow for rerun operations:
note right of newExecutor: rerun(workflowId, rerunFromTaskId, taskInput, correlationId)
note right of newExecutor: where taskInput is also processed by WorkflowPreProcessor.

@enduml
Component Breakdown
SecretManagerDAO (Core Interface & DB-Specific Implementations)

Location: Defined as an interface in the Conductor core module.
Purpose: Provides a contract for interacting with the underlying secret storage.
Implementations: Specific database modules (e.g., conductor-postgres) will provide concrete implementations (e.g., PostgresSecretManagerDAO). This DAO is responsible for retrieving secret values from the secrets table in the database.
Example (Postgres): PostgresSecretManagerDAO will interact with the PostgreSQL database to fetch secrets.
SecretManagerService (Core Service)

Location: A service class in the Conductor core module.
Purpose: Acts as an intermediary between the WorkflowPreProcessor and the SecretManagerDAO. It orchestrates the retrieval of secrets, including any necessary decryption if the DAO returns encrypted values.
Dependencies: Takes SecretManagerDAO as a constructor argument.
Annotation: Can be annotated with @Component for Spring to auto-discover and manage it.
WorkflowPreProcessor (Core Pre-processor)

Location: A class in the Conductor core module.
Purpose: Scans workflow definitions (specifically the input payload) for predefined secret patterns, retrieves the corresponding secret values using SecretManagerService, and replaces the patterns with the actual values.
Dependencies: Takes SecretManagerService as a constructor argument.
Secret Pattern: Recognizes patterns like $workflow.secrets.secret_name.
Methods: Includes methods to check for secret patterns (containsSecretsInMap) and to process a map, replacing secret patterns (processMap).
WorkflowExecutor Implementations (WorkflowExecutorOps & NewWorkflowExecutorOps)

WorkflowExecutorOps: This is the existing, default Conductor WorkflowExecutor implementation. It will not contain the secret injection logic directly.
NewWorkflowExecutorOps: This is a new implementation of the WorkflowExecutor interface.
Purpose: It wraps or overrides the core workflow execution logic to include the secret injection step before the workflow is created and evaluated by the underlying execution engine.
Dependencies: Takes WorkflowPreProcessor as a constructor argument.
Conditional Loading: This class will be enabled via a Spring @ConditionalOnProperty annotation (e.g., conductor.secrets.enabled=true). When this property is set, NewWorkflowExecutorOps will be the primary WorkflowExecutor bean, and WorkflowExecutorOps will be excluded or take a lower precedence.
Methods modified/intercepted:
startWorkflow(StartWorkflowInput input): Before creating the WorkflowModel, this method will utilize WorkflowPreProcessor to scan input.getWorkflowInput() for secret patterns and replace them.
rerun(String workflowId, String rerunFromTaskId, Map<String, Object> taskInput, String correlationId) (or the public method that calls rerunWF): Similarly, this method will use WorkflowPreProcessor to process taskInput if it contains secret patterns.
Implementation Details
Database Schema (secrets table)
A new table secrets (or similar) will be introduced in the Conductor database.

SQL

CREATE TABLE secrets (
id SERIAL PRIMARY KEY,
name VARCHAR(255) UNIQUE NOT NULL,
value TEXT NOT NULL,
encryption_key_id VARCHAR(255), -- Optional: Reference to key used for encryption
created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Optional: Add index for faster lookups
CREATE INDEX idx_secrets_name ON secrets (name);
name: The unique identifier for the secret (e.g., my_api_key).
value: The actual secret value. This should be encrypted at rest in the database.
encryption_key_id: (Optional) A reference to the key used for encryption, useful if multiple encryption keys are used.
Spring Configuration
The PostgresConfiguration class (or similar database configuration) will be updated to include the SecretManagerDAO bean.

Java

// In com.netflix.conductor.postgres.PostgresConfiguration
import com.netflix.conductor.dao.SecretManagerDAO;
import com.netflix.conductor.postgres.dao.PostgresSecretManagerDAO;
// ... other imports

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PostgresProperties.class)
@ConditionalOnProperty(name = "conductor.db.type", havingValue = "postgres")
@Import(DataSourceAutoConfiguration.class)
public class PostgresConfiguration {

    // ... existing constructor and other beans

    @Bean
    @DependsOn({"flywayForPrimaryDb"})
    public SecretManagerDAO secretManagerDAO(DataSource dataSource) {
        // Assuming your PostgresSecretManagerDAO handles decryption if value is encrypted at rest
        return new PostgresSecretManagerDAO(dataSource);
    }

    // ... other existing DAO beans
}
The SecretManagerService and WorkflowPreProcessor will be standard Spring @Component beans, as you mentioned, allowing for auto-wiring.

The conditional loading of WorkflowExecutor will be managed using @ConditionalOnProperty:

Java

// Original Conductor WorkflowExecutorOps (if not modified)
@Trace
@Component
@ConditionalOnProperty(name = "conductor.secrets.enabled", havingValue = "false", matchIfMissing = true)
public class WorkflowExecutorOps implements WorkflowExecutor {
// ... original Conductor logic
}

// New Secret-Aware WorkflowExecutorOps
@Trace
@Component
@ConditionalOnProperty(name = "conductor.secrets.enabled", havingValue = "true", matchIfMissing = false)
public class NewWorkflowExecutorOps implements WorkflowExecutor {

    private final WorkflowExecutor actualConductorExecutor; // This would be the original WorkflowExecutorOps or similar
    private final WorkflowPreProcessor workflowPreProcessor;
    // ... other dependencies

    public NewWorkflowExecutorOps(
            @Lazy WorkflowExecutor actualConductorExecutor, // Use @Lazy to avoid circular dependency
            WorkflowPreProcessor workflowPreProcessor,
            // ... other original dependencies
            ) {
        this.actualConductorExecutor = actualConductorExecutor;
        this.workflowPreProcessor = workflowPreProcessor;
        // ... initialize other dependencies of the actualConductorExecutor
    }

    @Override
    public String startWorkflow(StartWorkflowInput input) {
        // --- NEW: SECRET INJECTION LOGIC ---
        Map<String, Object> workflowInput = input.getWorkflowInput();
        if (workflowInput != null && workflowPreProcessor.containsSecretsInMap(workflowInput)) {
            LOGGER.info("Secrets detected in workflow input for definition {}. Resolving secrets...", input.getName());
            input.setWorkflowInput(workflowPreProcessor.processMap(workflowInput));
            LOGGER.info("Secrets resolved for workflow input for definition {}", input.getName());
        } else {
            LOGGER.debug("No secrets detected in workflow input for definition {}. Skipping secret resolution.", input.getName());
        }
        // --- END NEW LOGIC ---

        // Delegate to the actual Conductor workflow execution logic
        return actualConductorExecutor.startWorkflow(input);
    }

    @Override
    public void rerun(String workflowId, String rerunFromTaskId, Map<String, Object> taskInput, String correlationId) {
        // --- NEW: SECRET INJECTION LOGIC FOR RERUN ---
        Map<String, Object> processedTaskInput = taskInput;
        if (taskInput != null && workflowPreProcessor.containsSecretsInMap(taskInput)) {
            LOGGER.info("Secrets detected in rerun task input for workflowId: {}. Resolving secrets...", workflowId);
            processedTaskInput = workflowPreProcessor.processMap(taskInput);
            LOGGER.info("Secrets resolved for rerun task input for workflowId: {}", workflowId);
        } else {
            LOGGER.debug("No secrets detected in rerun task input for workflowId: {}. Skipping secret resolution.", workflowId);
        }
        // --- END NEW LOGIC ---

        // Delegate to the actual Conductor rerun logic
        actualConductorExecutor.rerun(workflowId, rerunFromTaskId, processedTaskInput, correlationId);
    }

    // ... other WorkflowExecutor method implementations, delegating to actualConductorExecutor
    // and potentially processing inputs if they can contain secrets.
}
The NewWorkflowExecutorOps would either wrap the actualConductorExecutor and call its methods after preprocessing, or it would essentially copy the logic of the WorkflowExecutorOps and insert the workflowPreProcessor calls where needed. The wrapping approach is generally cleaner.

Secret Pattern
Secrets will be referenced in workflow input JSON using the format: "$workflow.secrets.secret_name".

Example Workflow Definition Snippet:

JSON

{
"name": "my_secure_workflow",
"version": 1,
"inputParameters": [
"apiKey",
"dbPassword"
],
"tasks": [
{
"name": "call_secure_api",
"taskReferenceName": "call_secure_api_ref",
"inputParameters": {
"api_key": "$workflow.secrets.my_api_key",
"endpoint": "https://api.example.com/v1"
},
"type": "SIMPLE"
},
{
"name": "database_operation",
"taskReferenceName": "db_op_ref",
"inputParameters": {
"db_user": "admin",
"db_pass": "$workflow.secrets.production_db_password"
},
"type": "SIMPLE"
}
]
}
Considerations and Trade-offs
Encryption at Rest: It is crucial that secret values stored in the secrets table are encrypted. The SecretManagerDAO or a layer above it should handle decryption upon retrieval.
Performance Overhead:
Impact: Introducing database lookups and string replacements adds latency to workflow and task initiation. For workflows with many secret references or high-frequency invocations, this could be noticeable.
Mitigation: Implement a caching layer (e.g., Guava LoadingCache or Caffeine) within the SecretManagerService to cache frequently accessed secret values. This significantly reduces database round trips. Ensure cache expiry is configured to align with your secret rotation policies.
Testing: Conduct performance testing to assess the actual impact on workflow start times and overall throughput.
Error Handling:
Missing Secrets: Define how the system should behave if a referenced secret ($workflow.secrets.non_existent_secret) is not found in the database. Options include:
Throwing an error and failing the workflow start.
Logging a warning and leaving the pattern unreplaced (less secure).
Replacing with a default/empty value. (Failing the workflow is generally preferred for security).
Decryption Failures: Handle cases where a retrieved secret cannot be decrypted.
Conductor Upgrade Compatibility:
The use of @ConditionalOnProperty for WorkflowExecutor implementations is an excellent approach. This allows for a clean transition and enables administrators to choose whether to activate the secret management feature. If conductor.secrets.enabled is false or not set, the original WorkflowExecutorOps will be used, ensuring backward compatibility.
Side Effects on Other WorkflowExecutor Methods:
You are correct in identifying that startWorkflow and methods related to rerun (like the public rerun method in the WorkflowExecutor interface that calls rerunWF internally) are the primary candidates for secret injection because they handle initial workflow/task inputs.
Important: You need to review all methods within the WorkflowExecutor interface and its implementations (e.g., WorkflowExecutorOps) that take Map<String, Object> or similar structures as input, which could potentially contain user-defined data with secret patterns. For instance, methods that modify task inputs during runtime, though less common for direct secret injection, should be considered if they allow external input with secret patterns. However, startWorkflow and rerun are the most critical points.
Security Access Control: Ensure that access to the secrets table in the database is tightly controlled, limiting it only to the Conductor service account with read-only permissions.
Future Enhancements
Integration with External Secret Managers: Extend SecretManagerDAO to integrate with dedicated secret management services like AWS Secrets Manager, HashiCorp Vault, Azure Key Vault, etc., instead of storing secrets directly in the workflow database. This provides a more robust and scalable solution for enterprise environments.
Dynamic Secret Rotation: Implement mechanisms for automatic secret rotation and ensure that the caching layer respects these rotations by having appropriate cache invalidation policies.
UI for Secret Management: Develop a Conductor UI component for easier management (create, read, update, delete) of secrets.
