package com.ywdrtt.conductor.worker;


import com.ywdrtt.conductor.worker.abstractions.ConductorWorker;
import com.ywdrtt.conductor.worker.abstractions.TaskHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@ConductorWorker("multiplyby2")
public class MultiplyBy2 {

    @TaskHandler
    public Map<String, Object> handle(Map<String, Object> input) {
        Integer inputNum;
        try {
            log.info("MultipleBy2 input: {} ", input);
            Object raw = input.get("added");

            if (raw instanceof String) {
                inputNum = Integer.parseInt((String) raw);
            } else if (raw instanceof Number) {
                inputNum = ((Number) raw).intValue();
            } else {
                throw new IllegalArgumentException("Input 'added' must be a number.");
            }

        } catch (Exception e) {
            log.error("Invalid input: {}", input.get("added"), e);
            throw e;
        }

        int result = inputNum * 2;
        log.info("multiplyby2: {} * 2 = {}", result, result);

        return Map.of("mb2", result);
    }
}
