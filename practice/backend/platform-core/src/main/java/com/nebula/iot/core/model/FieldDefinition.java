package com.nebula.iot.core.model;

import com.nebula.iot.core.internal.Checks;
import java.util.Objects;

/** Input or event field; required applies to a complete invocation or event. */
public record FieldDefinition(String id, ValueSchema schema, boolean required) {
    public FieldDefinition {
        id = Checks.nonBlank(id, "fieldId");
        Objects.requireNonNull(schema, "schema");
    }
}
