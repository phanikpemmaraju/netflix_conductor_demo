package com.ywdrtt.conductor.secuity;

public enum UserRole {
    USER,
    READ_ONLY_USER,
    WORKFLOW_MANAGER,
    METADATA_MANAGER,
    ADMIN;

    public String withPrefix() {
        return "ROLE_" + this.name();
    }
    public String withoutPrefix() {
        return this.name();
    }
}