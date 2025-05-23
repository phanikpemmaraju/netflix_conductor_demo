@startuml
title 1. Idempotent: Updating User Profile (Success & Retry)

actor User
participant "Client App" as Client
participant "API Server" as Server
database "Database" as DB

User -> Client: Changes email (new@example.com)
Client -> Server: PUT /api/users/{userId}/profile { "email": "new@example.com" }

activate Server
Server -> DB: Update User.email
DB --> Server: Update successful
Server --> Client: 200 OK (Response lost due to network)
deactivate Server

... Network Issue / Timeout ...

Client -> Server: RETRY: PUT /api/users/{userId}/profile { "email": "new@example.com" }
activate Server
Server -> DB: Check if User.email is already 'new@example.com'
DB --> Server: User.email is already 'new@example.com'
Server --> Client: 200 OK (Idempotent: No further change)
deactivate Server
@enduml



@startuml
title 2. Idempotent: Canceling an Order (Success & Retry)

actor User
participant "Client App" as Client
participant "API Server" as Server
database "Database" as DB

User -> Client: Cancels order
Client -> Server: DELETE /api/orders/{orderId}

activate Server
Server -> DB: Change Order.status to 'Canceled'
DB --> Server: Update successful
Server --> Client: 204 No Content (Response lost due to network)
deactivate Server

... Network Issue / Timeout ...

Client -> Server: RETRY: DELETE /api/orders/{orderId}
activate Server
Server -> DB: Check Order.status
DB --> Server: Order.status is 'Canceled'
Server --> Client: 204 No Content (or 404 Not Found if truly removed)
deactivate Server
@enduml



@startuml
title 3. Non-Idempotent: Submitting a Payment (Double Charge)

actor User
participant "Client App" as Client
participant "API Server" as Server
database "Database" as DB

User -> Client: Initiates payment ($50)
Client -> Server: POST /api/payments { "amount": 50 }

activate Server
Server -> DB: Create new Payment (ID: P1)
DB --> Server: Payment P1 created
Server --> Client: 201 Created (Response lost due to network)
deactivate Server

... Network Issue / Timeout ...

Client -> Server: RETRY: POST /api/payments { "amount": 50 }
activate Server
Server -> DB: Create new Payment (ID: P2 - distinct from P1)
DB --> Server: Payment P2 created
Server --> Client: 201 Created
deactivate Server
@enduml



@startuml
title 4. Non-Idempotent: Posting a New Comment (Duplicate Comments)

actor User
participant "Client App" as Client
participant "API Server" as Server
database "Database" as DB

User -> Client: Submits comment "Great article!"
Client -> Server: POST /api/posts/{postId}/comments { "content": "Great article!" }

activate Server
Server -> DB: Create Comment C1
DB --> Server: Comment C1 created
Server --> Client: 201 Created (Response delayed/User double clicks)
deactivate Server

... Slow Network / User Clicks Again ...

Client -> Server: RETRY: POST /api/posts/{postId}/comments { "content": "Great article!" }
activate Server
Server -> DB: Create Comment C2 (distinct from C1)
DB --> Server: Comment C2 created
Server --> Client: 201 Created
deactivate Server
@enduml
