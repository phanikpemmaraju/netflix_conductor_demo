package com.ywdrtt.conductor.worker;

import com.ywdrtt.conductor.worker.abstractions.ConductorWorker;
import com.ywdrtt.conductor.worker.abstractions.TaskHandler;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@ConductorWorker("addnumbers")
public class AddNumbersWorker {

    @TaskHandler
    public Map<String, Object> thisCanBeAnything(Map<String, Object> input) {
        int sum = Integer.parseInt((String) input.get("num1")) +
                Integer.parseInt((String) input.get("num2"));

        log.info("Handled add numbers task: {} + {} = {}", input.get("num1"), input.get("num2"), sum);
        return Map.of("addition", sum);
    }
}
