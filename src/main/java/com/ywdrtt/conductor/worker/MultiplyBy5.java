package com.ywdrtt.conductor.worker;

import com.ywdrtt.conductor.worker.abstractions.ConductorWorker;
import com.ywdrtt.conductor.worker.abstractions.TaskHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@ConductorWorker("multiplyby5")
public class MultiplyBy5 {

    @TaskHandler
    public Map<String, Object> handle(Map<String, Object> input) {
        Integer inputNum;
        try {
            log.info("MultipleBy5 input: {} ", input);
            Object raw = input.get("doubled");

            if (raw instanceof String) {
                inputNum = Integer.parseInt((String) raw);
            } else if (raw instanceof Number) {
                inputNum = ((Number) raw).intValue();
            } else {
                throw new IllegalArgumentException("Input 'doubled' must be a number.");
            }

        } catch (Exception e) {
            log.error("Invalid input: {}", input.get("doubled"), e);
            throw e;
        }

        int result = inputNum * 5;
        log.info("multiplyby5: {} * 5 = {}", result, result);

        return Map.of("mb5", result);
    }
}
