package com.nebula.iot.rule;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Defensive snapshots of JSON values; no arbitrary application objects. */
final class JsonValues {
    private JsonValues() {}

    static String identifier(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Identifier must not be blank");
        return value;
    }

    static Map<String, Object> map(Map<String, Object> source) {
        Objects.requireNonNull(source, "JSON object");
        var copy = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "JSON key"), snapshot(value, 0)));
        return Collections.unmodifiableMap(copy);
    }

    static Object snapshot(Object value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting exceeds 64 levels");
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) return value;
        if (value instanceof Double d && Double.isFinite(d)) return d;
        if (value instanceof Float f && Float.isFinite(f)) return f;
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("JSON keys must be strings");
                copy.put(key, snapshot(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<Object>();
            for (var item : list) copy.add(snapshot(item, depth + 1));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }
}
