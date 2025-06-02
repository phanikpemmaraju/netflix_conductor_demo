@startuml
actor User

participant "Conductor OSS\nWorkflow Engine" as Engine
participant "System HTTP Task\n(Custom Interceptor)" as Interceptor
participant "Secrets Manager\n(Token Store)" as Secrets
participant "Target Service API" as API

User -> Engine : Start workflow
Engine -> Interceptor : Execute System HTTP Task\nwith credentials = "service-role-credential"
Interceptor -> Secrets : Fetch access token\nfor "service-role-credential"
Secrets --> Interceptor : Return access token
Interceptor -> API : HTTP Request\nAuthorization: Bearer <token>
API --> Interceptor : Response
Interceptor --> Engine : Return task result
Engine --> User : Return task result

@enduml