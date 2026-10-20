package com.nebula.iot.core.model;

import com.nebula.iot.core.internal.Checks;
import java.util.Map;

public record EventDefinition(String id, Map<String, FieldDefinition> fields, String name, String description) {
    public EventDefinition(String id, Map<String, FieldDefinition> fields) { this(id, fields, "", ""); }
    public EventDefinition {
        name = ModelMetadata.text(name, 100, "name");
        description = ModelMetadata.text(description, 1000, "description");
        id = Checks.nonBlank(id, "eventId");
        fields = ModelFields.copy(fields);
    }

    public void validateFields(Map<String, Value> values) {
        ModelFields.validate(fields, values);
    }
}
