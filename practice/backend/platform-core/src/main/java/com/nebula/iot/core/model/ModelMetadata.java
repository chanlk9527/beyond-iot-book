package com.nebula.iot.core.model;

/** Missing metadata remains compatible with previously published model snapshots. */
final class ModelMetadata {
    private ModelMetadata() { }
    static String text(String value, int limit, String field) {
        if (value == null) return "";
        value = value.trim();
        if (value.codePointCount(0, value.length()) > limit)
            throw new IllegalArgumentException(field + " exceeds " + limit + " characters");
        return value;
    }
}