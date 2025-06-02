package com.ywdrtt.conductor.secuity;

package com.yourcompany.app.controller;

import com.yourcompany.app.enums.ApplicationRole;
import com.yourcompany.app.enums.Permission;
import com.yourcompany.app.enums.UserRole;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/tasks")
public class TaskController {

    private static String roles(Enum<?>... roles) {
        return Arrays.stream(roles)
                .map(Enum::name)
                .collect(Collectors.joining("','", "'", "'"));
    }

    @GetMapping("/poll")
    @PreAuthorize("hasAnyRole(" + roles(ApplicationRole.WORKER, UserRole.ADMIN) + ")")
    public String pollTasks() {
        return "Polling for tasks (Accessed by Worker or Admin)";
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasPermission(#id, 'Task', '" + Permission.WORKFLOW_POLL + "') or hasAnyRole(" + roles(UserRole.ADMIN) + ")")
    public String completeTask(@PathVariable String id) {
        return "Task " + id + " completed (Accessed by Worker or Admin)";
    }
}