package com.nebula.iot.core.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable typed values avoid mutable or transport-specific Object payloads. */
public sealed interface Value {
    ValueType type();

    record BooleanValue(boolean value) implements Value {
        @Override public ValueType type() { return ValueType.BOOLEAN; }
    }

    record IntegerValue(long value) implements Value {
        @Override public ValueType type() { return ValueType.INTEGER; }
    }

    record DecimalValue(BigDecimal value) implements Value {
        public DecimalValue { Objects.requireNonNull(value, "value"); }
        @Override public ValueType type() { return ValueType.DECIMAL; }
    }

    record TextValue(String value) implements Value {
        public TextValue { Objects.requireNonNull(value, "value"); }
        @Override public ValueType type() { return ValueType.TEXT; }
    }
    /** A single dimension; the schema validates the homogeneous element type. */
    record ArrayValue(java.util.List<Value> value) implements Value {
        public ArrayValue {
            value = java.util.List.copyOf(value);
            if (value.size() > 1000 || value.stream().anyMatch(v -> v instanceof ArrayValue))
                throw new IllegalArgumentException("Arrays support at most 1000 scalar elements");
        }
        @Override public ValueType type() { return ValueType.ARRAY; }
    }}
