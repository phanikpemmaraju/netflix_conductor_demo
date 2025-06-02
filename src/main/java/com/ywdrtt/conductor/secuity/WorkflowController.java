package com.ywdrtt.conductor.secuity;

package com.yourcompany.app.controller;

import com.yourcompany.app.enums.ApplicationRole;
import com.yourcompany.app.enums.Permission;
import com.yourcompany.app.enums.UserRole;
import com.yourcompany.app.model.Workflow;
import com.yourcompany.app.service.WorkflowService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    private static String roles(Enum<?>... roles) {
        return Arrays.stream(roles)
                .map(Enum::name)
                .collect(Collectors.joining("','", "'", "'"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Workflow', '" + Permission.WORKFLOW_VIEW + "') or " +
            "hasAnyRole(" + roles(UserRole.ADMIN, ApplicationRole.UNRESTRICTED_WORKER) + ")")
    public Workflow getWorkflow(@PathVariable Long id) {
        return workflowService.findById(id);
    }

    @PostMapping("/{id}/execute")
    @PreAuthorize("hasPermission(#id, 'Workflow', '" + Permission.WORKFLOW_EXECUTE + "') or " +
            "hasAnyRole(" + roles(UserRole.ADMIN, ApplicationRole.UNRESTRICTED_WORKER) + ")")
    public void executeWorkflow(@PathVariable Long id) {
        workflowService.executeWorkflow(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole(" + roles(UserRole.WORKFLOW_MANAGER, UserRole.ADMIN, ApplicationRole.UNRESTRICTED_WORKER) + ")")
    public Workflow createWorkflow(@RequestBody Workflow newWorkflow) {
        return workflowService.save(newWorkflow);
    }
}