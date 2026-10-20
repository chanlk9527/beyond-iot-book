package com.nebula.iot.core.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Optional bounds use null to mean unconstrained. Text length is measured in code points. */
public record ValueSchema(ValueType type, BigDecimal minimum, BigDecimal maximum, Integer maxLength,
                          java.util.Map<String, String> enumValues, String unit,
                          ValueSchema items, Integer minItems, Integer maxItems) {
    public ValueSchema(ValueType type, BigDecimal minimum, BigDecimal maximum, Integer maxLength,
                       java.util.Map<String, String> enumValues, String unit) {
        this(type, minimum, maximum, maxLength, enumValues, unit, null, null, null);
    }
    public ValueSchema(ValueType type, BigDecimal minimum, BigDecimal maximum, Integer maxLength) {
        this(type, minimum, maximum, maxLength, java.util.Map.of(), "");
    }
    public ValueSchema {
        Objects.requireNonNull(type, "type");
        if (type == ValueType.ARRAY) {
            if (items == null || items.type() == ValueType.ARRAY)
                throw new IllegalArgumentException("Array requires a scalar items schema");
            if ((minItems != null && (minItems < 0 || minItems > 1000))
                    || (maxItems != null && (maxItems < 0 || maxItems > 1000))
                    || (minItems != null && maxItems != null && minItems > maxItems))
                throw new IllegalArgumentException("Array length must satisfy 0 <= minItems <= maxItems <= 1000");
        } else if (items != null || minItems != null || maxItems != null)
            throw new IllegalArgumentException("Only ARRAY supports items and array length constraints");
        unit = ModelMetadata.text(unit, 32, "unit");
        enumValues = enumValues == null ? java.util.Map.of() : java.util.Map.copyOf(enumValues);
        if (type == ValueType.ENUM) {
            if (enumValues.isEmpty() || enumValues.size() > 100)
                throw new IllegalArgumentException("Enum requires 1 to 100 options");
            enumValues.forEach((code, label) -> {
                if (code.isBlank() || code.codePointCount(0, code.length()) > 64 || label.isBlank()
                        || label.codePointCount(0, label.length()) > 100)
                    throw new IllegalArgumentException("Enum requires non-blank codes (64) and labels (100)");
            });
        } else if (!enumValues.isEmpty()) throw new IllegalArgumentException("Only ENUM supports enumValues");
        boolean numeric = type == ValueType.INTEGER || type == ValueType.DECIMAL;
        if (!numeric && !unit.isEmpty()) throw new IllegalArgumentException("Only numeric schemas support units");
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
        if (type == ValueType.ARRAY) {
            if (!(value instanceof Value.ArrayValue array)) throw new IllegalArgumentException("Expected ARRAY");
            if (array.value().size() < (minItems == null ? 0 : minItems)
                    || array.value().size() > (maxItems == null ? 1000 : maxItems))
                throw new IllegalArgumentException("Array length is outside model bounds");
            array.value().forEach(items::validate);
            return;
        }
        if (type == ValueType.ENUM) {
            if (!(value instanceof Value.TextValue text)) throw new IllegalArgumentException("Expected string for ENUM");
            if (!enumValues.containsKey(text.value())) throw new IllegalArgumentException("Unknown enum value: " + text.value());
            return;
        }
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
