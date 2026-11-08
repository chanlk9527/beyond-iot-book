package com.nebula.iot.rule;

/** Closed structured condition model; no executable scripts. */
public sealed interface RuleCondition permits Condition, ConditionGroup {
    boolean matches(RuleData data);
}
