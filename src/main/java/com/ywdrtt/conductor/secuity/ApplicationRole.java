package com.ywdrtt.conductor.secuity;

// Example: com.yourcompany.app.enums.ApplicationRole.java
public enum ApplicationRole {
    WORKER("worker"),
    METADATA_API("metadata_api"),
    APPLICATION_API("application_api"),
    UNRESTRICTED_WORKER("unrestricted_worker"),
    APP_METADATA_MANAGER("app_metadata_manager"), // ADD THIS
    APP_WORKFLOW_MANAGER("app_workflow_manager"), // ADD THIS
    APP_APPLICATION_MANAGER("app_application_manager"), // ADD THIS
    APP_ADMIN("app_admin"); // ADD THIS

    private final String roleName;

    ApplicationRole(String roleName) {
        this.roleName = roleName;
    }

    public String withPrefix() {
        // This should return the exact string that your JwtTokenConverterConfig produces
        // based on the JWT claims for application roles.
        // For example, if your JwtTokenConverterConfig creates authorities like "APP_ADMIN", then return that.
        // If it creates "ROLE_APP_ADMIN", then include "ROLE_" prefix here.
        // Assuming it's just the role name itself from the enum for direct matching with GrantedAuthority.
        return this.name(); // Returns "WORKER", "METADATA_API", "APP_ADMIN", etc.
    }
}