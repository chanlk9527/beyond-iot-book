package com.nebula.iot.rule;

import java.util.Optional;

/** Synchronous calculation only. Empty output stops this branch; no delivery/retry guarantee. */
@FunctionalInterface
public interface TaskExecutor {
    Optional<RuleData> execute(ExecutionContext context, RuleData input);
}
