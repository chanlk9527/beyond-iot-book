package com.nebula.iot.core.model;

import com.nebula.iot.core.internal.Checks;
import java.util.Map;

/** Execution result and completion guarantees belong to the operation's protocol contract. */
public record OperationDefinition(String id, Map<String, FieldDefinition> inputs, String name, String description) {
    public OperationDefinition(String id, Map<String, FieldDefinition> inputs) { this(id, inputs, "", ""); }
    public OperationDefinition {
        name = ModelMetadata.text(name, 100, "name");
        description = ModelMetadata.text(description, 1000, "description");
        id = Checks.nonBlank(id, "operationId");
        inputs = ModelFields.copy(inputs);
    }

    public void validateInputs(Map<String, Value> values) {
        ModelFields.validate(inputs, values);
    }
}
