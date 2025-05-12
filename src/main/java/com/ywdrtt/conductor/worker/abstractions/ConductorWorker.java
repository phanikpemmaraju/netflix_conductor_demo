package com.ywdrtt.conductor.worker.abstractions;
import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConductorWorker {
    String value(); // Task Name
}
