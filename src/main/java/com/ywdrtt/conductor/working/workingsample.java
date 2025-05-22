Conditional Spring Security in Conductor OSS
This document explains how our customized Conductor OSS application leverages Spring Boot's conditional configuration capabilities to dynamically enable or disable Spring Security based on a simple application property. This provides flexibility for different environments (e.g., local development, testing, production) without requiring code changes or recompilation.
        1. The Problem: Flexible Security Posture
In an enterprise product, the security requirements can vary significantly across different environments:
Local Development/Testing: Developers often prefer to run the application without full security overhead for faster iteration and easier debugging.
        Integration/Staging: Security might be partially enabled or configured for specific tests.
Production: Full, robust security is mandatory.
Hardcoding security configurations or relying on complex build-time flags can lead to:
Developer friction.
Accidental exposure of unsecured endpoints.
Cumbersome deployment processes.
2. The Solution: Property-Driven Conditional Security
We address this by using Spring Boot's @ConditionalOnProperty annotation in conjunction with custom meta-annotations and distinct Spring Security configuration classes. This allows Spring to decide which security configuration to apply at application startup, based on the value of a single property: conductor.security.enabled.
Key Principle: Default to Secured
By default, if the conductor.security.enabled property is missing or set to true, the application will be secured. It will only be unsecured if the property is explicitly set to false. This ensures a "secure by default" posture.
3. Key Components
3.1. The Control Property
The central control point for security enablement is the application property:
conductor.security.enabled:
Set to true to enable Spring Security.
Set to false to disable Spring Security (permit all requests).
If this property is missing, security will be enabled by default.
        3.2. Custom Conditional Annotations
We've created two custom annotations that act as wrappers around @ConditionalOnProperty, making the configuration classes more readable and intent-driven.
@ApplicationSecured
This annotation is placed on the configuration class that defines the secured Spring Security setup.
        package com.yourcompany.conductor.security.annotations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom annotation to conditionally enable Spring Security configuration.
 * This configuration will be active when 'conductor.security.enabled' property
 * is set to 'true', or if the property is missing (defaulting to enabled).
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(
        name = "conductor.security.enabled",
        havingValue = "true",
        matchIfMissing = true // If 'conductor.security.enabled' is missing, this condition is TRUE
)
public @interface ApplicationSecured {
}


@ApplicationNotSecured
This annotation is placed on the configuration class that defines the unsecured (permissive) Spring Security setup.
package com.yourcompany.conductor.security.annotations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom annotation to conditionally disable Spring Security configuration (i.e., permit all access).
 * This configuration will be active only when 'conductor.security.enabled' property
 * is explicitly set to 'false'. It will NOT be active if the property is missing.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(
        name = "conductor.security.enabled",
        havingValue = "false",
        matchIfMissing = false // If 'conductor.security.enabled' is missing, this condition is FALSE
)
public @interface ApplicationNotSecured {
}


3.3. Spring Security Configuration Classes
Two distinct SecurityFilterChain configurations are defined, each activated by one of the custom annotations.
        SecuredSecurityConfig
This class defines the actual security rules when security is enabled. For now, it simply requires all requests to be authenticated. Method-level security (@PreAuthorize, @PostAuthorize) is also enabled.
        package com.yourcompany.conductor.security.config;

import com.yourcompany.conductor.security.annotations.ApplicationSecured;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true) // Enable method-level security
@ApplicationSecured // Activated when conductor.security.enabled=true or missing
public class SecuredSecurityConfig {

    @Bean
    public SecurityFilterChain securedFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // Disable CSRF for API-driven applications
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().authenticated() // All requests must be authenticated
                )
                .formLogin(); // Simple form login for demonstration (can be replaced with JWT, OAuth2, etc.)

        return http.build();
    }
}


OpenSecurityConfig
This class defines a permissive security setup, allowing all requests when security is disabled. Method-level security is explicitly disabled here.
        package com.yourcompany.conductor.security.config;

import com.yourcompany.conductor.security.annotations.ApplicationNotSecured;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = false) // Disable method-level security
@ApplicationNotSecured // Activated when conductor.security.enabled=false
public class OpenSecurityConfig {

    @Bean
    public SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // Disable CSRF for API-driven applications
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().permitAll() // Permit all requests
                );
        return http.build();
    }
}


4. How It Works
Application Startup: When the Conductor OSS Spring Boot application starts, Spring's component scanning discovers both SecuredSecurityConfig and OpenSecurityConfig.
Conditional Evaluation: For each configuration class, Spring evaluates its associated @ConditionalOnProperty annotation against the active environment properties (from application.properties, environment variables, etc.).
Exclusive Activation:
If conductor.security.enabled is true (or missing), the condition for @ApplicationSecured evaluates to true, and SecuredSecurityConfig is loaded. The condition for @ApplicationNotSecured evaluates to false, so OpenSecurityConfig is not loaded.
If conductor.security.enabled is false, the condition for @ApplicationNotSecured evaluates to true, and OpenSecurityConfig is loaded. The condition for @ApplicationSecured evaluates to false, so SecuredSecurityConfig is not loaded.
Single SecurityFilterChain: As a result, only one SecurityFilterChain bean is ever created and active in the Spring application context, ensuring a consistent security posture.
        5. Usage
To control the security behavior, simply set the conductor.security.enabled property in your application.properties (or application.yml), or via an environment variable.
        5.1. Enable Security (Production Default)
To enable security (requiring authentication for all requests):
        # application.properties
conductor.security.enabled=true


OR (by default if property is missing):
        # application.properties
# (property not present)


        5.2. Disable Security (Local Development/Testing)
To disable security (permit all requests):
        # application.properties
conductor.security.enabled=false


        5.3. Via Environment Variable (e.g., in Docker/Kubernetes)
CONDUCTOR_SECURITY_ENABLED=false java -jar conductor-server.jar


Or in Kubernetes Deployment YAML:
env:
        - name: CONDUCTOR_SECURITY_ENABLED
value: "false"


        6. Benefits
Flexibility: Easily switch security postures for different environments.
No Code Changes: Control security via configuration, not code.
"Secure by Default": Reduces the risk of accidentally deploying an unsecured application.
Clean Architecture: Separates security concerns into distinct, conditionally loaded configurations.
Improved Developer Experience: Allows developers to quickly disable security for local testing.
Note on Code Embedding in Confluence:
When pasting the code blocks above into Confluence, you can enhance their presentation and collapsibility by using the Confluence {code} macro. For example, you can wrap each code block like this:
        {code:java|title=ApplicationSecured.java|collapse=true}
// Your Java code here
        {code}


Adjust the java language, title, and collapse parameters as needed for each snippet.
