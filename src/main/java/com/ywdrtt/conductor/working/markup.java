@startuml
autonumber
actor "Resource Owner" as RO
participant "Client Application" as CA
participant "Authorization Server (IdP)" as AS
participant "Resource Server" as RS

RO -> CA: 1. Request Protected Resource
activate CA

CA -> AS: 2. HTTP Redirect to /authorize\n(response_type=code, client_id, redirect_uri, scope)
activate AS
AS --> RO: 3. Display Login & Consent Form
RO --> AS: 4. Grants Consent (Login/Approve)

AS -> CA: 5. HTTP Redirect to redirect_uri\n(code=AUTHORIZATION_CODE, state=...)
deactivate AS

CA -> AS: 6. POST /token\n(grant_type=authorization_code, code, redirect_uri, client_id, client_secret)
activate AS
AS --> CA: 7. Response: Access Token, Refresh Token, expires_in, token_type
deactivate AS

CA -> RS: 8. HTTP GET /resource\n(Authorization: Bearer ACCESS_TOKEN)
activate RS
RS -> AS: 9. Validate Access Token (e.g., introspection or JWT validation)
activate AS
AS --> RS: 10. Token Valid/Invalid
deactivate AS

RS --> CA: 11. Response: Protected Data
deactivate RS
CA --> RO: 12. Display Protected Data
deactivate CA
@enduml
