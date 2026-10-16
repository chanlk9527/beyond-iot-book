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
}
