graph LR
%% Define Custom Style for Workflow Executor Logic (Very Light Blue Background)
style WorkflowExecutor fill:#E3F2FD,stroke:#000,stroke-width:1px;

    %% Define Nodes
    A[Start Workflow]
    B[Conductor API]
    C{ConditionalOnProperty: secrets.enabled?}:::blueBackground

    %% Secret-Aware Execution Path
    D[WorkflowExecutorSecretOps]:::blueBackground
    E[Scan WorkflowDef for Secrets]:::blueBackground
    X{Secrets found in WorkflowDef?}:::blueBackground
    F[Call getSecret: my_secret]
    G[SecretManagerService]
    H[SecretClientAdapter]
    I[Retrieve Secret Value from Adapter]
    M[Return Secret Value]

    %% Standard Execution Path (No Secrets)
    Q[OriginalWorkflowExecutorOps]
    R[Task Execution]

    %% Workflow Execution Steps
    N[Processed Input - Secrets Replaced]
    O[Start Workflow with Processed Input]
    P[Workflow Execution Continues]

    %% Define Flow
    A --> B
    B --> C

    subgraph WorkflowExecutor["Workflow Executor Logic"]
        style WorkflowExecutor fill:#E3F2FD,stroke:#000,stroke-width:1px;
        C -- Yes --> D
        C -- No --> Q
        D --> E
        E --> X
        X -- Yes --> F
        X -- No --> R
    end

    F --> G

    subgraph "Secret Manager Service Flow"
        G --> H
        H --> I
        I --> M
    end

    M --> N
    N --> O
    O --> P
    P --> R
    Q --> R