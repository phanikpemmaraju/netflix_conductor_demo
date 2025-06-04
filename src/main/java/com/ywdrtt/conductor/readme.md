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







@startuml
!theme mars

actor User as user
participant "Conductor API" as api
participant "NewWorkflowExecutorOps\n(Secret-Aware)" as newExecutor
participant "WorkflowPreProcessor" as preProcessor
participant "SecretManagerService" as secretService
participant "SecretClientAdapter" as secretClient
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
secretService -> secretClient: getSecret("my_secret")
activate secretClient

    alt if AWS is configured
        secretClient -> awsSsm: GetParameter("my_secret", withDecryption=true)
        awsSsm --> secretClient: Secret Value
    else if Azure is configured
        secretClient -> azureVault: GetSecret("my_secret")
        azureVault --> secretClient: Secret Value
    end

    deactivate secretClient
    secretClient --> secretService: Plaintext Secret Value
    deactivate secretService
    secretService --> preProcessor: Plaintext Secret Value
    deactivate preProcessor
    preProcessor --> newExecutor: Processed Input (Secrets Replaced)
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