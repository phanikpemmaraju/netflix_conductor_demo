package com.ywdrtt.conductor.working;

package com.yourcompany.yourconductorapp.controller;

import org.springframework.security.access.prepost.PreAuthorize; // RE-ADDED
import org.springframework.web.bind.annotation.*;
import com.yourcompany.yourconductorapp.security.ConductorPermission; // Import the enum

@RestController
@RequestMapping("/")
public class ConductorAccessController {

    // --- workflow-resource ---
    // GET /workflow-resource/{workflowId} (user* / workflow manager)
    @GetMapping("/workflow-resource/{workflowId}")
    @PreAuthorize("hasPermission(#workflowId, 'workflow', T(com.yourcompany.yourconductorapp.security.ConductorPermission).READ)")
    public String getWorkflowResource(@PathVariable String workflowId) {
        return "Access granted to workflow resource: " + workflowId;
    }

    // POST /workflow-resource (user* / workflow manager)
    @PostMapping("/workflow-resource")
    @PreAuthorize("hasPermission('workflow', T(com.yourcompany.yourconductorapp.security.ConductorPermission).CREATE)") // No targetId initially for creation
    public String createWorkflowResource() {
        return "Access granted to create workflow resource";
    }

    // PUT /workflow-resource/{workflowId} (user* / workflow manager)
    @PutMapping("/workflow-resource/{workflowId}")
    @PreAuthorize("hasPermission(#workflowId, 'workflow', T(com.yourcompany.yourconductorapp.security.ConductorPermission).UPDATE)")
    public String updateWorkflowResource(@PathVariable String workflowId) {
        return "Access granted to update workflow resource: " + workflowId;
    }

    // DELETE /workflow-resource/{workflowId} (user* / workflow manager)
    @DeleteMapping("/workflow-resource/{workflowId}")
    @PreAuthorize("hasPermission(#workflowId, 'workflow', T(com.yourcompany.yourconductorapp.security.ConductorPermission).DELETE)")
    public String deleteWorkflowResource(@PathVariable String workflowId) {
        return "Access granted to delete workflow resource: " + workflowId;
    }


    // --- workflow-bulk-resource ---
    // GET, POST, PUT, DELETE /workflow-bulk-resource (workflow manager)
    @GetMapping("/workflow-bulk-resource")
    @PreAuthorize("hasPermission('workflow-bulk', T(com.yourcompany.yourconductorapp.security.ConductorPermission).READ)")
    public String getWorkflowBulkResource() {
        return "Access granted to workflow bulk resource";
    }

    @PostMapping("/workflow-bulk-resource")
    @PreAuthorize("hasPermission('workflow-bulk', T(com.yourcompany.yourconductorapp.security.ConductorPermission).CREATE)")
    public String createWorkflowBulkResource() {
        return "Access granted to create workflow bulk resource";
    }

    @PutMapping("/workflow-bulk-resource")
    @PreAuthorize("hasPermission('workflow-bulk', T(com.yourcompany.yourconductorapp.security.ConductorPermission).UPDATE)")
    public String updateWorkflowBulkResource() {
        return "Access granted to update workflow bulk resource";
    }

    @DeleteMapping("/workflow-bulk-resource")
    @PreAuthorize("hasPermission('workflow-bulk', T(com.yourcompany.yourconductorapp.security.ConductorPermission).DELETE)")
    public String deleteWorkflowBulkResource() {
        return "Access granted to delete workflow bulk resource";
    }


    // --- metadata-resource ---
    // GET, POST, PUT, DELETE /metadata-resource (user* / Metadata manager)
    @GetMapping("/metadata-resource/{metadataId}")
    @PreAuthorize("hasPermission(#metadataId, 'metadata', T(com.yourcompany.yourconductorapp.security.ConductorPermission).READ)")
    public String getMetadataResource(@PathVariable String metadataId) {
        return "Access granted to metadata resource: " + metadataId;
    }

    @PostMapping("/metadata-resource")
    @PreAuthorize("hasPermission('metadata', T(com.yourcompany.yourconductorapp.security.ConductorPermission).CREATE)")
    public String createMetadataResource() {
        return "Access granted to create metadata resource";
    }

    @PutMapping("/metadata-resource/{metadataId}")
    @PreAuthorize("hasPermission(#metadataId, 'metadata', T(com.yourcompany.yourconductorapp.security.ConductorPermission).UPDATE)")
    public String updateMetadataResource(@PathVariable String metadataId) {
        return "Access granted to update metadata resource: " + metadataId;
    }

    @DeleteMapping("/metadata-resource/{metadataId}")
    @PreAuthorize("hasPermission(#metadataId, 'metadata', T(com.yourcompany.yourconductorapp.security.ConductorPermission).DELETE)")
    public String deleteMetadataResource(@PathVariable String metadataId) {
        return "Access granted to delete metadata resource: " + metadataId;
    }


    // --- event-resource ---
    // GET, POST, PUT /event-resource (user*)
    @GetMapping("/event-resource/{eventId}")
    @PreAuthorize("hasPermission(#eventId, 'event', T(com.yourcompany.yourconductorapp.security.ConductorPermission).READ)")
    public String getEventResource(@PathVariable String eventId) {
        return "Access granted to event resource: " + eventId;
    }

    @PostMapping("/event-resource")
    @PreAuthorize("hasPermission('event', T(com.yourcompany.yourconductorapp.security.ConductorPermission).CREATE)")
    public String createEventResource() {
        return "Access granted to create event resource";
    }

    @PutMapping("/event-resource/{eventId}")
    @PreAuthorize("hasPermission(#eventId, 'event', T(com.yourcompany.yourconductorapp.security.ConductorPermission).UPDATE)")
    public String updateEventResource(@PathVariable String eventId) {
        return "Access granted to update event resource: " + eventId;
    }


    // --- task-resource ---
    // GET /task-resource (user*)
    @GetMapping("/task-resource/{taskId}")
    @PreAuthorize("hasPermission(#taskId, 'task', T(com.yourcompany.yourconductorapp.security.ConductorPermission).READ)")
    public String getTaskResource(@PathVariable String taskId) {
        return "Access granted to task resource: " + taskId;
    }


    // --- queue-admin-resource ---
    // GET, POST /queue-admin-resource (admin)
    @GetMapping("/queue-admin-resource")
    @PreAuthorize("hasPermission('queue-admin', T(com.yourcompany.yourconductorapp.security.ConductorPermission).READ)") // Example, could be MANAGE or ADMIN
    public String getQueueAdminResource() {
        return "Access granted to queue admin resource for admin";
    }

    @PostMapping("/queue-admin-resource")
    @PreAuthorize("hasPermission('queue-admin', T(com.yourcompany.yourconductorapp.security.ConductorPermission).CREATE)") // Example, could be MANAGE or ADMIN
    public String createQueueAdminResource() {
        return "Access granted to create queue admin resource for admin";
    }


    // --- admin-resource ---
    // GET, POST /admin-resource (admin)
    @GetMapping("/admin-resource")
    @PreAuthorize("hasPermission('admin', T(com.yourcompany.yourconductorapp.security.ConductorPermission).READ)") // Example, could be ADMIN
    public String getAdminResource() {
        return "Access granted to admin resource for admin";
    }

    @PostMapping("/admin-resource")
    @PreAuthorize("hasPermission('admin', T(com.yourcompany.yourconductorapp.security.ConductorPermission).CREATE)") // Example, could be ADMIN
    public String createAdminResource() {
        return "Access granted to create admin resource for admin";
    }


    // --- health-check-resource ---
    // GET /health-check-resource (user*) - configured to permitAll in SecurityConfig for broader access
    // No @PreAuthorize needed here as it's handled by permitAll in SecurityConfig,
    // but if it wasn't, you could use:
    // @PreAuthorize("hasPermission('health-check', T(com.yourcompany.yourconductorapp.security.ConductorPermission).READ)")
    @GetMapping("/health-check-resource")
    public String getHealthCheckResource() {
        return "Health check resource. Accessible to all (based on SecurityConfig permitAll).";
    }
}
