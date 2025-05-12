package com.ywdrtt.conductor.worker.config;


import com.netflix.conductor.client.automator.TaskRunnerConfigurer;
import com.netflix.conductor.client.http.TaskClient;
import com.netflix.conductor.client.worker.Worker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConductorWorkerStarter implements SmartInitializingSingleton {

    private final TaskClient taskClient;
    private final ConductorWorkerAutoRegistrar registrar;
    private TaskRunnerConfigurer configurer;

    @Override
    public void afterSingletonsInstantiated() {
        List<Worker> workers = registrar.getRegisteredWorkers();

        if (!workers.isEmpty()) {
            configurer = new TaskRunnerConfigurer.Builder(taskClient, workers)
                    .withThreadCount(workers.size())
                    .build();

            configurer.init();
            log.info("✅ Started {} Conductor workers ", workers.size());
        } else {
            log.warn("⚠️ No Conductor workers found to register.");
        }
    }

    @PreDestroy
    public void shutdown() {
        if (configurer != null) {
            configurer.shutdown();
            log.info("🛑 Conductor workers shut down cleanly.");
        }
    }
}
