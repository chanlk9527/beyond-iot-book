package com.nebula.iot.core.model;

import java.util.Map;

final class ModelFields {
    private ModelFields() { }

    static Map<String, FieldDefinition> copy(Map<String, FieldDefinition> fields) {
        var copy = Map.copyOf(fields);
        copy.forEach((key, definition) -> {
            if (!key.equals(definition.id())) {
                throw new IllegalArgumentException("Field map key must match field ID");
            }
        });
        return copy;
    }

    static void validate(Map<String, FieldDefinition> definitions, Map<String, Value> values) {
        var snapshot = Map.copyOf(values);
        definitions.forEach((id, field) -> {
            if (field.required() && !snapshot.containsKey(id)) {
                throw new IllegalArgumentException("Missing required field: " + id);
            }
        });
        snapshot.forEach((id, value) -> {
            var definition = definitions.get(id);
            if (definition == null) {
                throw new IllegalArgumentException("Unknown field: " + id);
            }
            definition.schema().validate(value);
        });
    }
}
