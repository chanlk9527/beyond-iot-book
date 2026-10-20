package com.nebula.iot.core.model;

import com.nebula.iot.core.internal.Checks;
import java.util.Objects;

/** Input or event field; required applies to a complete invocation or event. */
public record FieldDefinition(String id, ValueSchema schema, boolean required, String name, String description) {
    public FieldDefinition(String id, ValueSchema schema, boolean required) { this(id, schema, required, "", ""); }
    public FieldDefinition {
        name = ModelMetadata.text(name, 100, "name");
        description = ModelMetadata.text(description, 1000, "description");
        id = Checks.nonBlank(id, "fieldId");
        Objects.requireNonNull(schema, "schema");
    }
}
