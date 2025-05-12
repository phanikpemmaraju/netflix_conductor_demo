package com.ywdrtt.conductor.worker.config;

import com.netflix.conductor.client.http.TaskClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class ConductorClientConfig {

    // Task Client Bean
    @Bean
    public TaskClient taskClient() {
        TaskClient client = new TaskClient();
        client.setRootURI("http://localhost:8080/api/"); // Conductor server URL
        log.info("Task Client Registered: {} ", client);
        return client;
    }
}