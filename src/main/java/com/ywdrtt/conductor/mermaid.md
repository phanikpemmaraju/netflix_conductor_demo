graph LR
A[   User   ]
B[   Conductor API   ]
C[   WorkflowExecutor<br>Secret-Aware Wrapper   ]
D{   Secrets enabled?   }

    %% Workflow Definition Scanning Process
    E[   Scan WorkflowDef<br>for Secrets   ]
    X{   Secrets found in<br>WorkflowDef?   }
    
    %% Secret Retrieval Process
    F[   Call getSecret: my_secret   ]
    G[   SecretManagerService   ]
    H[   SecretClientAdapter   ]
    I[   Retrieve Secret Value<br>from Adapter   ]
    M[   Return Secret Value   ]
    
    %% Workflow Execution Steps
    N[   Processed Input<br>Secrets Replaced   ]
    O[   Start Workflow<br>with Processed Input   ]
    P[   Workflow Execution Continues   ]
    Q[   Original WorkflowExecutor<br>Default   ]
    R[   Task Execution   ]

    %% Define Flow
    A --> B
    B --> C

    subgraph "Workflow Executor Logic"
        C --> D
        D -- Yes --> E
        D -- No --> Q
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