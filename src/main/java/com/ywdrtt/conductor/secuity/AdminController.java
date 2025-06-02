package com.ywdrtt.conductor.secuity;

package com.yourcompany.app.controller;

import com.yourcompany.app.enums.UserRole;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays; // Added import
import java.util.stream.Collectors; // Added import

@RestController
@RequestMapping("/admin")
public class AdminController {

    private static String roles(Enum<?>... roles) {
        return Arrays.stream(roles)
                .map(Enum::name)
                .collect(Collectors.joining("','", "'", "'"));
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole(" + roles(UserRole.ADMIN) + ")")
    public String getAdminDashboard() {
        return "Welcome to the Admin Dashboard!";
    }

    @GetMapping("/users")
    @PreAuthorize("hasAnyRole(" + roles(UserRole.ADMIN) + ")")
    public String manageUsers() {
        return "User management interface.";
    }
}