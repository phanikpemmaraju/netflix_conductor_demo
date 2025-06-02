package com.ywdrtt.conductor.secuity;

public enum ApplicationRole {
    WORKER,
    METADATA_API,
    APPLICATION_API,
    UNRESTRICTED_WORKER;

    public String withPrefix() {
        return "ROLE_" + this.name();
    }
    public String withoutPrefix() {
        return this.name();
    }
}