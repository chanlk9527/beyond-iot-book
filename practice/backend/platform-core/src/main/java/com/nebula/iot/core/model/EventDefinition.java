package com.nebula.iot.core.model;

import com.nebula.iot.core.internal.Checks;
import java.util.Map;

public record EventDefinition(String id, Map<String, FieldDefinition> fields) {
    public EventDefinition {
        id = Checks.nonBlank(id, "eventId");
        fields = ModelFields.copy(fields);
    }

    public void validateFields(Map<String, Value> values) {
        ModelFields.validate(fields, values);
    }
}
