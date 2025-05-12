package com.ywdrtt.conductor.worker.abstractions;

import java.util.Map;

public interface ConductorTaskHandler {
    Map<String, Object> handle(Map<String, Object> input);
}
