package com.nebula.iot.rule;

import java.math.BigDecimal;
import java.util.*;

/** Projection to a top-level output key; numeric conversion is explicit. */
public record FieldMapping(String target, String source, boolean constant, Object value,
                           BigDecimal multiplier, BigDecimal offset) {
    public FieldMapping {
        JsonValues.identifier(target);
        if (target.contains(".")) throw new IllegalArgumentException("Mapping target must be a top-level key");
        value = JsonValues.snapshot(value, 0);
        if (constant) {
            if (source != null || multiplier != null || offset != null)
                throw new IllegalArgumentException("Constant mapping cannot reference or transform fields");
        } else {
            new Condition(source, Condition.Operator.EQ, null); // validates the shared dot-path syntax
            if (value != null) throw new IllegalArgumentException("Field mapping cannot specify a constant");
        }
    }
    public static FieldMapping field(String target, String source, BigDecimal multiplier, BigDecimal offset) {
        return new FieldMapping(target, source, false, null, multiplier, offset);
    }
    public static FieldMapping constant(String target, Object value) {
        return new FieldMapping(target, null, true, value, null, null);
    }
    public Object evaluate(RuleData data) {
        if (constant) return value;
        Object current = data.values();
        for (String part : source.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part))
                throw new IllegalArgumentException("Mapping source is missing: " + source);
            current = map.get(part);
        }
        if (multiplier == null && offset == null) return current;
        if (!(current instanceof Number number)) throw new IllegalArgumentException("Mapping source must be numeric: " + source);
        return new BigDecimal(number.toString()).multiply(multiplier == null ? BigDecimal.ONE : multiplier)
                .add(offset == null ? BigDecimal.ZERO : offset);
    }
    static List<FieldMapping> validate(List<FieldMapping> fields) {
        fields = List.copyOf(fields);
        if (fields.size() > 256) throw new IllegalArgumentException("At most 256 mappings supported");
        var targets = new HashSet<String>();
        for (var field : fields) if (!targets.add(field.target())) throw new IllegalArgumentException("Duplicate mapping target: " + field.target());
        return fields;
    }
    public Map<String, Object> configuration() {
        var result = new LinkedHashMap<String, Object>();
        result.put("target", target);
        if (constant) result.put("value", value);
        else {
            result.put("source", source);
            if (multiplier != null) result.put("multiplier", multiplier);
            if (offset != null) result.put("offset", offset);
        }
        return JsonValues.map(result);
    }
}
