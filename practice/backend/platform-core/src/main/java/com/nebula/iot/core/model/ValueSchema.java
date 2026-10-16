package com.nebula.iot.core.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Optional bounds use null to mean unconstrained. Text length is measured in code points. */
public record ValueSchema(ValueType type, BigDecimal minimum, BigDecimal maximum, Integer maxLength) {
    public ValueSchema {
        Objects.requireNonNull(type, "type");
        boolean numeric = type == ValueType.INTEGER || type == ValueType.DECIMAL;
        if (!numeric && (minimum != null || maximum != null)) {
            throw new IllegalArgumentException("Only numeric schemas support numeric bounds");
        }
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Minimum must not exceed maximum");
        }
        if (type == ValueType.INTEGER && ((minimum != null && minimum.stripTrailingZeros().scale() > 0)
                || (maximum != null && maximum.stripTrailingZeros().scale() > 0))) {
            throw new IllegalArgumentException("Integer bounds must be integral");
        }
        if (maxLength != null && (type != ValueType.TEXT || maxLength < 0)) {
            throw new IllegalArgumentException("Non-negative maxLength requires a text schema");
        }
    }

    public static ValueSchema of(ValueType type) {
        return new ValueSchema(type, null, null, null);
    }

    public void validate(Value value) {
        Objects.requireNonNull(value, "value");
        boolean widening = type == ValueType.DECIMAL && value instanceof Value.IntegerValue;
        if (value.type() != type && !widening) {
            throw new IllegalArgumentException("Expected " + type + ", received " + value.type());
        }
        BigDecimal numeric = switch (value) {
            case Value.IntegerValue integer -> BigDecimal.valueOf(integer.value());
            case Value.DecimalValue decimal -> decimal.value();
            default -> null;
        };
        if (numeric != null && ((minimum != null && numeric.compareTo(minimum) < 0)
                || (maximum != null && numeric.compareTo(maximum) > 0))) {
            throw new IllegalArgumentException("Numeric value is outside model bounds");
        }
        if (value instanceof Value.TextValue text && maxLength != null
                && text.value().codePointCount(0, text.value().length()) > maxLength) {
            throw new IllegalArgumentException("Text exceeds model length");
        }
    }
}
