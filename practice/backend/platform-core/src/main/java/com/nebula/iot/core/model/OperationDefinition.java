package com.nebula.iot.core.model;

import com.nebula.iot.core.internal.Checks;
import java.util.Map;

/** Execution result and completion guarantees belong to the operation's protocol contract. */
public record OperationDefinition(String id, Map<String, FieldDefinition> inputs) {
    public OperationDefinition {
        id = Checks.nonBlank(id, "operationId");
        inputs = ModelFields.copy(inputs);
    }

    public void validateInputs(Map<String, Value> values) {
        ModelFields.validate(inputs, values);
    }
}
