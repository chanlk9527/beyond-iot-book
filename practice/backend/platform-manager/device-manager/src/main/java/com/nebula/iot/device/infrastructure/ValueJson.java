package com.nebula.iot.device.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.nebula.iot.core.model.Value;
import java.util.ArrayList;

/** Management JSON adapter; uses the same core array invariants as the wire adapter. */
public final class ValueJson {
    private ValueJson() { }
    public static Value fromJson(JsonNode node) {
        if (node == null || node.isNull()) throw new IllegalArgumentException("Value cannot be null");
        if (node.isArray()) {
            if (node.size() > 1000) throw new IllegalArgumentException("Array exceeds 1000 elements");
            var values = new ArrayList<Value>();
            for (var item : node) {
                if (item.isContainerNode()) throw new IllegalArgumentException("Array elements must be scalar");
                values.add(fromJson(item));
            }
            return new Value.ArrayValue(values);
        }
        if (node.isBoolean()) return new Value.BooleanValue(node.booleanValue());
        if (node.isIntegralNumber() && node.canConvertToLong()) return new Value.IntegerValue(node.longValue());
        if (node.isFloatingPointNumber()) return new Value.DecimalValue(node.decimalValue());
        if (node.isTextual()) return new Value.TextValue(node.textValue());
        throw new IllegalArgumentException("Expected boolean, number, string or scalar array");
    }
    public static JsonNode toJson(Value value) {
        return switch (value) {
            case Value.BooleanValue v -> BooleanNode.valueOf(v.value());
            case Value.IntegerValue v -> LongNode.valueOf(v.value());
            case Value.DecimalValue v -> DecimalNode.valueOf(v.value());
            case Value.TextValue v -> TextNode.valueOf(v.value());
            case Value.ArrayValue v -> {
                var node = JsonNodeFactory.instance.arrayNode();
                v.value().forEach(item -> node.add(toJson(item)));
                yield node;
            }
        };
    }
}