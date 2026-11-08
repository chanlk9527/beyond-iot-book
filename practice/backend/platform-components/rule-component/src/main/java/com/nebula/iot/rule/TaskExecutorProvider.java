package com.nebula.iot.rule;

import java.util.Map;

/** Providers must be thread-safe; validation and creation must not perform business side effects. */
public interface TaskExecutorProvider {
    String type();
    void validate(Map<String, Object> configuration);
    /** Create a fresh executor per node/run. Executors must not own resources requiring disposal. */
    TaskExecutor create(Map<String, Object> configuration);
}
