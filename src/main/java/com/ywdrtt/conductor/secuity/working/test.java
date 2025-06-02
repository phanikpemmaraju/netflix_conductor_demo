package com.yourcompany.conductor.filter; // Recommend a package name

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections; // For empty enumeration

@Component
public class OAuth2TokenInjectionFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Apply filter only to POST requests to /api/workflow
        if (request.getRequestURI().equals("/api/workflow") && request.getMethod().equalsIgnoreCase("POST")) {
            String authHeader = request.getHeader("Authorization");

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                // Read the request body
                String body = IOUtils.toString(request.getInputStream(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(body);
                JSONObject input = json.optJSONObject("input");

                // If "input" object doesn't exist, create it
                if (input == null) {
                    input = new JSONObject();
                    json.put("input", input);
                }

                // Inject the access_token into the workflow input
                input.put("access_token", authHeader); // Keep the "Bearer " prefix for now, can be stripped later if needed

                // Create the new body bytes
                byte[] newBody = json.toString().getBytes(StandardCharsets.UTF_8);

                // Create a wrapped request with the modified body
                HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(request) {
                    private final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(newBody);

                    @Override
                    public ServletInputStream getInputStream() {
                        return new DelegatingServletInputStream(byteArrayInputStream);
                    }

                    @Override
                    public BufferedReader getReader() throws IOException {
                        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
                    }

                    @Override
                    public int getContentLength() {
                        return newBody.length;
                    }

                    @Override
                    public long getContentLengthLong() {
                        return newBody.length;
                    }

                    // Override getHeader, getHeaders, getHeaderNames to ensure Content-Length is correct
                    // This is crucial for some web servers/frameworks to correctly parse the request.
                    @Override
                    public String getHeader(String name) {
                        if (name.equalsIgnoreCase("Content-Length")) {
                            return String.valueOf(newBody.length);
                        }
                        return super.getHeader(name);
                    }

                    @Override
                    public java.util.Enumeration<String> getHeaders(String name) {
                        if (name.equalsIgnoreCase("Content-Length")) {
                            return Collections.enumeration(Collections.singletonList(String.valueOf(newBody.length)));
                        }
                        return super.getHeaders(name);
                    }

                    @Override
                    public java.util.Enumeration<String> getHeaderNames() {
                        // Create a mutable list from original headers and add/replace Content-Length
                        java.util.List<String> headerNames = Collections.list(super.getHeaderNames());
                        if (!headerNames.contains("Content-Length")) {
                            headerNames.add("Content-Length");
                        }
                        return Collections.enumeration(headerNames);
                    }
                };

                // Continue the filter chain with the wrapped request
                filterChain.doFilter(wrappedRequest, response);
                return; // Important: prevent further processing of the original request
            }
        }

        // For all other requests or if no Bearer token is found, proceed with the original request
        filterChain.doFilter(request, response);
    }

    // Helper class for DelegatingServletInputStream (often part of Spring Web)
    // If you don't have Spring Web, you might need to implement this or a similar wrapper.
    private static class DelegatingServletInputStream extends ServletInputStream {
        private final InputStream sourceStream;

        public DelegatingServletInputStream(InputStream sourceStream) {
            this.sourceStream = sourceStream;
        }

        @Override
        public int read() throws IOException {
            return this.sourceStream.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return this.sourceStream.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            try {
                return sourceStream.available() == 0;
            } catch (IOException e) {
                return false; // Or handle appropriately
            }
        }

        @Override
        public boolean isReady() {
            return true; // Always ready for a ByteArrayInputStream
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Not implemented for this type of stream");
        }
    }
}

+++++++++


        package com.yourcompany.conductor.interceptor; // Recommend a package name

import com.netflix.conductor.contribs.http.HttpTask;
import com.netflix.conductor.model.TaskModel; // Corrected import for Task
import com.netflix.conductor.model.TaskResult;
import com.netflix.conductor.model.WorkflowModel; // Corrected import for WorkflowInstance

import java.util.HashMap; // For creating headers map if null
import java.util.Map;

public class OAuth2TokenInjectingInterceptor implements HttpTask.HttpTaskInterceptor {

    @Override
    public void preProcess(TaskModel task, HttpTask.HttpInput httpInput) {
        // Retrieve workflow input
        WorkflowModel workflowInstance = task.getWorkflowInstance();
        if (workflowInstance == null) {
            // This shouldn't happen for a task within a workflow, but good to guard
            return;
        }
        Map<String, Object> workflowInput = workflowInstance.getInput();

        // Retrieve task input
        Map<String, Object> taskInput = task.getInputData();

        // Determine authentication strategy, default to "Service"
        String authStrategy = (String) taskInput.getOrDefault("authStrategy", "Service");

        // Ensure httpInput.getHeaders() is not null before putting values
        if (httpInput.getHeaders() == null) {
            httpInput.setHeaders(new HashMap<>());
        }

        if ("User".equalsIgnoreCase(authStrategy)) {
            // Get user token from workflow input
            String token = (String) workflowInput.get("access_token");
            if (token != null && !token.isBlank()) {
                httpInput.getHeaders().put("Authorization", token);
            }
        } else if ("Service".equalsIgnoreCase(authStrategy)) {
            // Inject a dummy service token (replace with actual service token retrieval logic)
            // In a real application, this service token would come from a secure configuration
            // or a token vending service.
            String serviceToken = "Bearer dummy-service-token";
            httpInput.getHeaders().put("Authorization", serviceToken);
        }
        // If authStrategy is neither "User" nor "Service", no token is injected by this interceptor.
    }

    @Override
    public void onResponse(TaskModel task, TaskResult result) {
        // Optional: Implement logic after the HTTP task completes.
        // For example, you could log the HTTP status, check for errors, or
        // modify the task result based on the HTTP response.
        // System.out.println("HTTP Task " + task.getTaskDefName() + " finished with status: " + result.getStatus());
    }
}


++++++++


        package com.yourcompany.conductor.config; // Recommend a package name

import com.netflix.conductor.contribs.http.HttpTask;
import com.yourcompany.conductor.interceptor.OAuth2TokenInjectingInterceptor; // Corrected import
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConductorConfig {

    @Bean
    public HttpTask.HttpTaskInterceptor oAuth2Interceptor() {
        return new OAuth2TokenInjectingInterceptor();
    }
}


+++++++


