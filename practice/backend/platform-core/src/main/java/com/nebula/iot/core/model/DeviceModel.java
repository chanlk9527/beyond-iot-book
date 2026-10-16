package com.nebula.iot.core.model;

import com.nebula.iot.core.device.ProductKey;
import com.nebula.iot.core.internal.Checks;
import java.util.Map;
import java.util.Objects;

/** An immutable, product-scoped model version. Reports may be partial. */
public record DeviceModel(ProductKey product, String version,
                          Map<String, PropertyDefinition> properties,
                          Map<String, EventDefinition> events,
                          Map<String, OperationDefinition> operations) {
    public DeviceModel {
        Objects.requireNonNull(product, "product");
        version = Checks.nonBlank(version, "version");
        properties = Map.copyOf(properties);
        events = Map.copyOf(events);
        operations = Map.copyOf(operations);
        properties.forEach((id, definition) -> requireMatchingKey(id, definition.id()));
        events.forEach((id, definition) -> requireMatchingKey(id, definition.id()));
        operations.forEach((id, definition) -> requireMatchingKey(id, definition.id()));
    }

    private static void requireMatchingKey(String key, String id) {
        if (!key.equals(id)) {
            throw new IllegalArgumentException("Model map key must match definition ID");
        }
    }

    public void validateReport(Map<String, Value> values) {
        Map.copyOf(values).forEach((id, value) -> {
            var property = properties.get(id);
            if (property == null || !property.access().reportable()) {
                throw new IllegalArgumentException("Unknown or non-reportable property: " + id);
            }
            property.schema().validate(value);
        });
    }

    /** 下行读取只能访问可上报属性。 */
    public void validatePropertyRead(java.util.Collection<String> ids) {
        ids.forEach(id -> {
            var property = properties.get(id);
            if (property == null || !property.access().reportable()) throw new IllegalArgumentException("不可读取属性：" + id);
        });
    }

    /** 下行写入必须满足访问权限及物模型值约束。 */
    public void validatePropertyWrite(Map<String, Value> values) {
        Map.copyOf(values).forEach((id, value) -> {
            var property = properties.get(id);
            if (property == null || property.access() == PropertyAccess.READ_ONLY) throw new IllegalArgumentException("不可写入属性：" + id);
            property.schema().validate(value);
        });
    }

    public void validateOperation(String operationId, Map<String, Value> inputs) {
        var operation = operations.get(operationId);
        if (operation == null) {
            throw new IllegalArgumentException("Unknown operation: " + operationId);
        }
        operation.validateInputs(inputs);
    }

    public void validateEvent(String eventId, Map<String, Value> fields) {
        var event = events.get(eventId);
        if (event == null) {
            throw new IllegalArgumentException("Unknown event: " + eventId);
        }
        event.validateFields(fields);
    }
}
