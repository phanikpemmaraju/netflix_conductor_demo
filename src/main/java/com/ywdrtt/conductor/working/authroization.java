Here's a Confluence page detailing the authorization implementation using OAuth2, drawing from the provided screenshots and incorporating your requested sections:

Page Title: OAuth2 Authorization & Role-Based Access Control (RBAC)

1. Overview

This document outlines our implementation of OAuth2 authorization and Role-Based Access Control (RBAC) within the application. We leverage Spring Security to secure our REST APIs, ensuring that only authenticated and authorized users or services can access specific resources based on their assigned roles.
Our application employs OAuth2 authorization and Role-Based Access Control (RBAC) to enforce security. Through the strategic use of Spring Security, we ensure that access to our REST APIs is strictly limited to authenticated and authorized principals, governed by their assigned roles.


        2. Core Components

Our authorization setup revolves around several key Spring Security components:

OAuth2 Resource Server: Our application acts as an OAuth2 Resource Server, validating incoming JSON Web Tokens (JWTs) issued by an Identity Provider (IdP).
JWT Authentication Converter (JwtAuthenticationConverter): This component is crucial for extracting authorities (roles) from the JWT claims and transforming them into Spring Security's GrantedAuthority objects.
SecurityFilterChain: Configured within SecurityEnabledConfiguration.java, this defines the security rules, including authorization policies for different API paths.
Custom AccessDeniedHandler: For granular control over 403 Forbidden responses, we've implemented a custom AccessDeniedHandler.
        3. Dynamic Role Extraction from JWT Claims

To provide flexibility and support various client configurations, we've implemented a dynamic mechanism for extracting roles from JWT claims. This is handled by JwtTokenConverterConfig.java and JwtGrantedAuthoritiesConverter.java.

JwtTokenConverterConfig.java: This class configures the JwtAuthenticationConverter. It sets a JwtGrantedAuthoritiesConverter to process the JWT claims.

        JwtGrantedAuthoritiesConverter.java: This custom converter is responsible for:

Iterating through predefined claim paths (configured via conductor.security.jwt.claims.role-paths in application.properties).
Using the extractListClaimsByPath method to navigate potentially nested JSON structures within the JWT claims and extract role values.
Transforming these extracted role values into SimpleGrantedAuthority objects, typically by prefixing them with "ROLE_" and converting them to uppercase.
        Merging default authorities with dynamically extracted custom authorities.
Example extractListClaimsByPath (from JwtTokenConverterConfig.java):
This method recursively traverses the JWT claims using a provided path (e.g., "realm_access.roles" or "resource_access.account.roles") to locate the list of roles. It handles cases where segments are maps or lists, ensuring robust extraction.

        Java

// Snippet from JwtTokenConverterConfig.java (IMG_2055.jpg)
private List<String> extractListClaimsByPath(Jwt jwt, String path) {
    Object currentClaim = jwt.getClaims();
    String[] segments = path.split("[:.]"); // Assuming path like "realm_access.roles" or "resource_access.account.roles"

    for (String segment : segments) {
        if (currentClaim instanceof Map) {
            currentClaim = ((Map<?, ?>) currentClaim).get(segment);
        } else if (currentClaim instanceof List) {
            // ... logic to flatten list or handle further segmentation
        } else {
            return List.of(); // Invalid path
        }
        if (currentClaim == null) return List.of();
    }

    if (currentClaim instanceof List) {
        return ((List<?>) currentClaim).stream()
                .map(Object::toString)
                .collect(Collectors.toList());
    }
    return List.of();
}
4. Authorization Rules (SecurityEnabledConfiguration.java)

The SecurityFilterChain bean defines the authorization rules using requestMatchers in SecurityEnabledConfiguration.java.

Role Definitions: We use securityRoleConfig (an @ConfigurationProperties bean, see IMG_2057.jpg and IMG_2056.jpg) to define roles like admin, workflowManager, metadataManager, and user via application.properties. These are then mapped to Spring Security roles using toSecurityRoles() which converts them to uppercase (e.g., "ADMIN").
Path-Based Authorization:
        /api/admin/**: Accessible by admin roles.
 /api/queue/**: GET methods require admin roles.
 /api/metadata/**: GET methods require admin roles.
 /api/workflow/**: GET methods require admin or workflowManager roles.
 /api/workflow/bulk/**: PUT and POST methods require workflowManager or admin roles.
 /api/event/**: GET/POST/PUT for user and admin roles.
 Any other request (anyRequest()) requires authentication (authenticated()).
 5. Custom 403 Forbidden Response

 When an authorization failure occurs (e.g., a user without the required role attempts to access a protected resource), Spring Security throws an AccessDeniedException. By default, this might result in a generic 403 response. To provide a more informative and consistent API experience, we have configured a CustomAccessDeniedHandler.

 CustomAccessDeniedHandler: This component, configured via exceptionHandling().accessDeniedHandler(customAccessDeniedHandler) in SecurityConfig, intercepts AccessDeniedException. It then crafts a custom JSON error response (e.g., ErrorResponse with status, message, timestamp, and path) and writes it directly to the HttpServletResponse, ensuring API consumers receive structured error information.
 6. @PreAuthorize and Permission Evaluators

 While requestMatchers provide coarse-grained authorization at the URL level, @PreAuthorize offers fine-grained, method-level security based on expressions.

 @PreAuthorize: This annotation can be placed on service methods or controller methods to define authorization rules using Spring Expression Language (SpEL). It runs before the method execution.

 Example: @PreAuthorize("hasRole('ADMIN') or hasPermission(#workflowId, 'Workflow', 'read')")
 This allows for complex authorization logic that depends on method arguments (e.g., specific workflowId).
 Permission Evaluator: For even more complex authorization requirements, especially object-level security, a custom PermissionEvaluator can be implemented.

 It defines how hasPermission(targetDomainObject, permission) or hasPermission(targetId, targetType, permission) expressions are evaluated.
 This is ideal when authorization depends on the state of a specific object (e.g., "can user X edit workflow Y?"). The PermissionEvaluator would query a database or external system to determine the permission.
 7. Trade-offs between Approaches

 Feature	requestMatchers (URL-based)	@PreAuthorize (Method-based)	Custom PermissionEvaluator (Object-based)
 Granularity	Coarse-grained (whole URL path)	Fine-grained (per method, can use method args)	Very fine-grained (per object instance)
 Ease of Use	Simple for common patterns, configured centrally in SecurityConfig.	Relatively easy with SpEL, annotation on method.	More complex setup, requires implementing PermissionEvaluator interface.
 Location	Centralized in SecurityConfig (SecurityFilterChain).	Distributed across controller/service methods.	Distributed across methods using annotation, logic centralized in PermissionEvaluator impl.
 Performance	Evaluated early in the filter chain, potentially preventing method execution.	Evaluated before method execution.	Can involve more complex lookups (e.g., DB queries) if logic is complex.
 Testability	Easy to test with integration tests against URL paths.	Can be tested with unit tests and mock objects.	Requires integration with data sources for comprehensive testing.
 Readability	Clear overview of API security at a glance in SecurityConfig.	Rules distributed, can make overall security harder to grasp.	PermissionEvaluator logic can be complex but cleanly separated.
 Coupling	Low coupling between security config and business logic.	Introduces coupling to Spring Security annotations in business logic.	Introduces coupling to Spring Security annotations, but logic is externalized.
 Use Case	General API access control (e.g., "only admins can access /admin/**").	Specific method access control (e.g., "only managers can approve workflows").	Object-level authorization (e.g., "only workflow creator or assignee can view workflow X").

 Export to Sheets
 8. Hybrid Approach (Recommended Solution)

 For most complex applications, a hybrid approach is the most effective:

 SecurityFilterChain (requestMatchers): Use this for coarse-grained, foundational security at the URL level. This handles broad access rules like "all /admin/** endpoints require an ADMIN role" or "all other endpoints require authentication." This provides a quick initial layer of defense.
 @PreAuthorize: Apply this for fine-grained, method-level security where authorization depends on method arguments or more complex business logic. For example, "a user can only view a workflow if they are involved in it."
 PermissionEvaluator: Implement a custom PermissionEvaluator when you need object-level security that queries external systems (like databases) to determine permissions based on specific object instances. This keeps complex authorization logic out of your SecurityConfig and service methods, making it more reusable and testable.
 This layered approach ensures that security is applied at the appropriate level, providing both performance and flexibility without overcomplicating any single component.


 Sources





