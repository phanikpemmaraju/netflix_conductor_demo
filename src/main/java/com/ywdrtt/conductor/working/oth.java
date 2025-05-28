@startuml
' Removed: skinparam handwritten true
skinparam actorStyle awesome

actor User as "User"
participant "Client UI (e.g., Web App)" as ClientUI
participant "Identity Provider\n(Authorization Server)" as IDP
participant "Resource Server" as RS

User -> ClientUI : 1. Accesses application / requests protected resource

ClientUI -> IDP : 2. Redirects to Identity Provider\n(Authorization Request with Client ID, Redirect URI, Scope, State)

User <-- IDP : 3. Identity Provider shows login/consent screen

User -> IDP : 4. User authenticates and grants consent

IDP -> ClientUI : 5. Redirects back to Client UI\n(Authorization Code + State)

ClientUI -> IDP : 6. Exchanges Authorization Code for Tokens\n(Client ID, Client Secret, Auth Code, Redirect URI)\n**backend communication**

IDP -> ClientUI : 7. Returns ID Token, Access Token, Refresh Token

ClientUI -> ClientUI : 8. Stores tokens securely\n(e.g., Access Token in memory, Refresh Token in secure storage)

ClientUI -> RS : 9. Requests protected resource\n(Includes Access Token in Authorization header: Bearer <Access Token>)

RS -> IDP : 10. Validates Access Token (Introspection/UserInfo/Local Validation)

IDP -> RS : 11. Returns Token Validation Result (e.g., active, scope, user info)

RS -> ClientUI : 12. Returns protected resource data

@enduml