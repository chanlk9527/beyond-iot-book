package com.nebula.iot.rule;

import java.util.Map;

/** Immutable JSON payload. Trusted identity is carried separately in ExecutionContext. */
public record RuleData(Map<String, Object> values) {
    public RuleData { values = JsonValues.map(values); }
}
