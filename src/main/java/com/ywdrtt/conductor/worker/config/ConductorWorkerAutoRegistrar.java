package com.ywdrtt.conductor.worker.config;

import com.netflix.conductor.client.worker.Worker;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.ywdrtt.conductor.worker.abstractions.ConductorWorker;
import com.ywdrtt.conductor.worker.abstractions.TaskHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.*;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ConductorWorkerAutoRegistrar implements BeanPostProcessor {
    private final List<Worker> dynamicWorkers = new ArrayList<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        ConductorWorker annotation = bean.getClass().getAnnotation(ConductorWorker.class);
        if (annotation == null) return bean;

        String taskName = annotation.value();

        // Search for method annotated with @TaskHandler
        Optional<Method> maybeTaskHandler = Arrays.stream(bean.getClass().getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(TaskHandler.class))
                .findFirst();

        if (maybeTaskHandler.isEmpty()) {
            log.warn("Skipping worker registration for '{}': @TaskHandler method not found in class {}",
                    annotation.value(), bean.getClass().getSimpleName());
            return bean; // skip registration
        }

        Method taskHandler = maybeTaskHandler.get();

        // Validate signature
        if (!Map.class.isAssignableFrom(taskHandler.getReturnType()) ||
                taskHandler.getParameterCount() != 1 ||
                !Map.class.isAssignableFrom(taskHandler.getParameterTypes()[0])) {
            throw new IllegalStateException("Method " + taskHandler.getName() +
                    " must have signature: Map<String,Object> method(Map<String,Object>)");
        }

        Worker worker = new Worker() {
            public String getTaskDefName() { return taskName; }

            public TaskResult execute(Task task) {
                TaskResult result = new TaskResult(task);
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> output = (Map<String, Object>) taskHandler.invoke(bean, task.getInputData());
                    result.setOutputData(output);
                    result.setStatus(TaskResult.Status.COMPLETED);
                } catch (Exception e) {
                    result.setStatus(TaskResult.Status.FAILED);
                    result.setReasonForIncompletion(e.getMessage());
                }
                return result;
            }
        };

        dynamicWorkers.add(worker);
        log.info("Registered dynamic worker: {}", taskName);
        return bean;
    }

    public List<Worker> getRegisteredWorkers() {
        return Collections.unmodifiableList(dynamicWorkers);
    }
}


