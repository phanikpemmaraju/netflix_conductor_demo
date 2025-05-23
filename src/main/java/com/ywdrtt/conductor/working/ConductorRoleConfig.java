package com.ywdrtt.conductor.working;

package com.yourcompany.yourconductorapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component("conConfig")
public class ConductorRoleConfig {

    @Value("${app.roles.admin:ADMIN}")
    private List<String> adminRoles;

    @Value("${app.roles.workflowManager:WORKFLOW_MANAGER}")
    private List<String> workflowManagerRoles;

    @Value("${app.roles.metadataManager:METADATA_MANAGER}")
    private List<String> metadataManagerRoles;

    @Value("${app.roles.user:USER}")
    private List<String> userRoles;

    public List<String> adminRoles() {
        return adminRoles != null ? adminRoles : Collections.emptyList();
    }

    public List<String> workflowManagerRoles() {
        return workflowManagerRoles != null ? workflowManagerRoles : Collections.emptyList();
    }

    public List<String> metadataManagerRoles() {
        return metadataManagerRoles != null ? metadataManagerRoles : Collections.emptyList();
    }

    public List<String> userRoles() {
        return userRoles != null ? userRoles : Collections.emptyList();
    }
}
