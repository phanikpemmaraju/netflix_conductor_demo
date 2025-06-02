package com.ywdrtt.conductor.secuity;

package com.yourcompany.app.security;

import com.yourcompany.app.enums.ApplicationRole;
import com.yourcompany.app.enums.Permission;
import com.yourcompany.app.enums.UserRole;
import com.yourcompany.app.model.Metadata;
import com.yourcompany.app.model.MyResource;
import com.yourcompany.app.model.Workflow;
import com.yourcompany.app.service.MetadataService;
import com.yourcompany.app.service.MyResourceService;
import com.yourcompany.app.service.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays;

@Component
public class CustomPermissionEvaluator implements PermissionEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomPermissionEvaluator.class);

    private final MyResourceService myResourceService;
    private final WorkflowService workflowService;
    private final MetadataService metadataService;

    public CustomPermissionEvaluator(MyResourceService myResourceService, WorkflowService workflowService, MetadataService metadataService) {
        this.myResourceService = myResourceService;
        this.workflowService = workflowService;
        this.metadataService = metadataService;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if ((authentication == null) || (targetDomainObject == null) || !(permission instanceof String)) {
            LOGGER.debug("Permission check failed: Invalid authentication, target object, or permission type.");
            return false;
        }

        String targetType = targetDomainObject.getClass().getSimpleName().toUpperCase();
        String perm = (String) permission;

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        LOGGER.debug("Evaluating object-level permission: targetType={}, permission={}, principal={}, authorities={}",
                targetType, perm, authentication.getName(), authorities);

        if (authorities.contains(UserRole.ADMIN.withPrefix())) {
            return true;
        }

        if ("MYRESOURCE".equals(targetType)) {
            MyResource resource = (MyResource) targetDomainObject;
            String currentUserId = authentication.getName();
            if (resource.getOwnerId() != null && resource.getOwnerId().equals(currentUserId)) {
                if (perm.equals(Permission.RESOURCE_READ.name()) ||
                        perm.equals(Permission.RESOURCE_UPDATE.name()) ||
                        perm.equals(Permission.RESOURCE_DELETE.name())) {
                    return true;
                }
            }
        }

        if ("WORKFLOW".equals(targetType)) {
            if (authorities.contains(UserRole.WORKFLOW_MANAGER.withPrefix()) ||
                    authorities.contains(ApplicationRole.UNRESTRICTED_WORKER.withPrefix())) {
                if (perm.equals(Permission.WORKFLOW_VIEW.name()) || perm.equals(Permission.WORKFLOW_EXECUTE.name())) {
                    return true;
                }
            }
            if (authorities.contains(UserRole.READ_ONLY_USER.withPrefix()) && perm.equals(Permission.WORKFLOW_VIEW.name())) {
                return true;
            }
        }

        if ("METADATA".equals(targetType)) {
            if (authorities.contains(UserRole.METADATA_MANAGER.withPrefix()) ||
                    authorities.contains(ApplicationRole.METADATA_API.withPrefix())) {
                if (perm.equals(Permission.METADATA_READ.name()) ||
                        perm.equals(Permission.METADATA_UPDATE.name()) ||
                        perm.equals(Permission.METADATA_DELETE.name())) {
                    return true;
                }
            }
            if (authorities.contains(UserRole.READ_ONLY_USER.withPrefix()) && perm.equals(Permission.METADATA_READ.name())) {
                return true;
            }
        }

        if ("SECRET".equals(targetType) && perm.equals(Permission.SECRET_READ.name())) {
            return !authorities.contains(UserRole.READ_ONLY_USER.withPrefix());
        }

        if ("TASK".equals(targetType) && perm.equals(Permission.WORKFLOW_POLL.name()) && authorities.contains(ApplicationRole.WORKER.withPrefix())) {
            return true;
        }
        if ("APPLICATION".equals(targetType) && authorities.contains(ApplicationRole.APPLICATION_API.withPrefix())) {
            if (perm.equals(Permission.APPLICATION_CREATE.name()) || perm.equals(Permission.APPLICATION_MANAGE.name())) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if ((authentication == null) || (targetId == null) || (targetType == null) || !(permission instanceof String)) {
            LOGGER.debug("Permission check failed: Invalid authentication, target ID, target type, or permission type.");
            return false;
        }

        String perm = (String) permission;
        String type = targetType.toUpperCase();

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        LOGGER.debug("Evaluating ID-based permission: targetId={}, targetType={}, permission={}, principal={}, authorities={}",
                targetId, type, perm, authentication.getName(), authorities);

        if (authorities.contains(UserRole.ADMIN.withPrefix())) {
            return true;
        }

        switch (type) {
            case "MYRESOURCE":
                MyResource resource = myResourceService.findById((Long) targetId);
                return hasPermission(authentication, resource, permission);
            case "WORKFLOW":
                Workflow workflow = workflowService.findById((Long) targetId);
                return hasPermission(authentication, workflow, permission);
            case "METADATA":
                Metadata metadata = metadataService.findById((Long) targetId);
                return hasPermission(authentication, metadata, permission);
            case "SECRET":
                return !authorities.contains(UserRole.READ_ONLY_USER.withPrefix());
            case "APPLICATION":
                return authorities.contains(ApplicationRole.APPLICATION_API.withPrefix());
            case "TASK":
                return authorities.contains(ApplicationRole.WORKER.withPrefix());
            default:
                return false;
        }
    }
}