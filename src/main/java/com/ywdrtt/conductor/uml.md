@startuml
!theme spacelab

actor "API Client" as client
participant "Spring Boot Filter\nAccessTokenInjectionFilter" as filter
participant "Conductor REST Controller" as controller
participant "WorkflowExecutorOps" as executor
database "Workflow DB" as db
database "Task Workers" as workers

== Workflow Start with Bearer Token ==

client -> filter: POST /api/workflow\nAuthorization: Bearer xyz123
activate filter

alt Token already injected
filter -> controller: proceed (unchanged request)
else
filter -> filter: Extract Authorization header
filter -> filter: Add token to request attribute/input map
filter -> controller: proceed (modified input map)
end
deactivate filter

controller -> executor: startWorkflow(input with access_token)
executor -> db: save workflow with access_token
db --> executor: OK
executor --> controller: workflowId
controller --> client: workflowId

== Later: Task Execution ==

executor -> workers: invoke task
note right of workers: Task reads input.access_token
@enduml
