Adding OAuth2 Bearer Token to Workflow Conductor OSS Inputs
Date: June 4, 2025

Purpose
This document outlines the implementation of a filter within the Workflow Conductor Open Source (OSS) to automatically extract the OAuth2 bearer token from incoming requests and inject it into the workflow's input. This allows all tasks within a given workflow to access the bearer token, enabling secure communication with external services or performing authenticated operations.

Background
Workflow Conductor OSS workflows, when enabled with OAuth2, require a bearer token for initiation. Previously, this token was not directly accessible within the workflow's context, posing challenges for tasks that needed to leverage the user's authentication for subsequent operations. This enhancement addresses that limitation by making the bearer token an integral part of the workflow input.

Implementation Details
The implementation involves a custom filter that intercepts incoming requests, extracts the Authorization header (specifically the bearer token), and modifies the workflow input to include this token.

Key Components:
FilterConfig.java: This class registers the custom filter.
AccessTokenInjectionFilter.java: This is the core filter logic responsible for token extraction and input injection.
Code Snippets (Illustrative)
1. FilterConfig.java

This configuration class registers the AccessTokenInjectionFilter as a Spring Bean, ensuring it's part of the filter chain for incoming requests.

Java

package com.netflix.conductor.rest.config;

import com.netflix.conductor.rest.filter.AccessTokenInjectionFilter;
import com.netflix.conductor.rest.config.RequestMappingConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    private final ObjectMapper objectMapper;

    public FilterConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public FilterRegistrationBean<AccessTokenInjectionFilter> filterRegistrationBean() {
        FilterRegistrationBean<AccessTokenInjectionFilter> filterRegistrationBean = new FilterRegistrationBean<>();
        filterRegistrationBean.setFilter(new AccessTokenInjectionFilter(objectMapper));
        filterRegistrationBean.addUrlPatterns(RequestMappingConstants.WORKFLOW); // Apply to workflow-related endpoints
        filterRegistrationBean.setDispatcherTypes(DispatcherType.REQUEST);
        return filterRegistrationBean;
    }
}
2. AccessTokenInjectionFilter.java

This filter intercepts requests, checks for a Bearer token in the Authorization header, and if found, injects it into the workflow input under the key "access_token".

Java

package com.netflix.conductor.rest.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netflix.conductor.common.utils.JsonMapperProvider; // Assuming this for ObjectMapper
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class AccessTokenInjectionFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public AccessTokenInjectionFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // A guard to make sure the token is applied once in the filter chain.
        if (request.getAttribute("filterApplied") != null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (request.getRequestURI().contains("workflow") && request.getMethod().equalsIgnoreCase("POST")) {
            String authHeader = request.getHeader("Authorization");

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String bearerToken = authHeader.substring(7); // Extract token after "Bearer "

                // Read the request body
                String stringBody = new String(request.getInputStream().readAllBytes());
                ObjectNode rootNode = (ObjectNode) objectMapper.readTree(stringBody);

                // Inject the token into the workflow input
                // Assuming the workflow input is directly under "input" or similar in the JSON payload
                if (rootNode.has("input") && rootNode.get("input").isObject()) {
                    ObjectNode existingInputNode = (ObjectNode) rootNode.get("input");
                    existingInputNode.put("access_token", bearerToken);
                } else {
                    // If no 'input' node exists, create one and add the token
                    ObjectNode inputNode = objectMapper.createObjectNode();
                    inputNode.put("access_token", bearerToken);
                    rootNode.put("input", inputNode);
                }

                byte[] newBody = objectMapper.writeValueAsBytes(rootNode);

                // Create a wrapped request with the modified body
                HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(request) {
                    private final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(newBody);

                    @Override
                    public ServletInputStream getInputStream() {
                        return new ServletInputStream() {
                            @Override
                            public boolean isFinished() {
                                return byteArrayInputStream.available() == 0;
                            }

                            @Override
                            public boolean isReady() {
                                return true;
                            }

                            @Override
                            public void setReadListener(jakarta.servlet.ReadListener readListener) {
                                // Not implemented for simplicity
                            }

                            @Override
                            public int read() throws IOException {
                                return byteArrayInputStream.read();
                            }
                        };
                    }

                    @Override
                    public BufferedReader getReader() throws IOException {
                        return new BufferedReader(new InputStreamReader(this.getInputStream(), StandardCharsets.UTF_8));
                    }

                    @Override
                    public int getContentLength() {
                        return newBody.length;
                    }
                };
                request.setAttribute("filterApplied", true); // Mark filter as applied
                filterChain.doFilter(wrappedRequest, response);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
Usage
Once this filter is deployed with your Workflow Conductor OSS instance, any workflow initiated with an Authorization: Bearer <token> header will have the <token> available within its input payload under the key "access_token".

Example Workflow Input:

If your workflow is initiated with a request containing:

Authorization: Bearer YOUR_OAUTH2_TOKEN
And an original request body like:

JSON

{
"workflowName": "MySecureWorkflow",
"version": 1,
"input": {
"param1": "value1",
"param2": "value2"
}
}
The workflow's internal input will be accessible to tasks as:

JSON

{
"param1": "value1",
"param2": "value2",
"access_token": "YOUR_OAUTH2_TOKEN"
}
Benefits
Simplified Task Authentication: Tasks within the workflow can directly access the bearer token from the input, simplifying calls to authenticated external services.
Centralized Token Management: The token is handled at the workflow initiation level, reducing the need for individual tasks to manage or re-authenticate.
Enhanced Security: Ensures that the same token used to initiate the workflow is consistently used for subsequent authenticated operations.
Future Considerations
Token Refresh: For long-running workflows, consider mechanisms to handle token expiration and refresh.
Token Scope Validation: Implement checks to ensure the bearer token has the necessary scopes for the operations performed within the workflow.
Error Handling: Enhance error handling for cases where the Authorization header is malformed or missing.
