package com.nebula.iot.core.model;

import com.nebula.iot.core.internal.Checks;
import java.util.Objects;

public record PropertyDefinition(String id, ValueSchema schema, PropertyAccess access, String name, String description) {
    public PropertyDefinition(String id, ValueSchema schema, PropertyAccess access) { this(id, schema, access, "", ""); }
    public PropertyDefinition {
        name = ModelMetadata.text(name, 100, "name");
        description = ModelMetadata.text(description, 1000, "description");
        id = Checks.nonBlank(id, "propertyId");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(access, "access");
    }
}
