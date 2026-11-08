package com.nebula.iot.rule;

/** A null condition means unconditional routing. */
public record RuleLink(String source, String target, RuleCondition condition) {
    public RuleLink {
        JsonValues.identifier(source);
        JsonValues.identifier(target);
    }
}
