package com.ywdrtt.conductor.worker.abstractions;

import java.lang.annotation.*;
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
// Annotation for the task method
public @interface TaskHandler {
}
