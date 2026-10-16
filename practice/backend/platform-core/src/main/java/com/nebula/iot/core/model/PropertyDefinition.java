package com.nebula.iot.core.model;

import com.nebula.iot.core.internal.Checks;
import java.util.Objects;

public record PropertyDefinition(String id, ValueSchema schema, PropertyAccess access) {
    public PropertyDefinition {
        id = Checks.nonBlank(id, "propertyId");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(access, "access");
    }
}
