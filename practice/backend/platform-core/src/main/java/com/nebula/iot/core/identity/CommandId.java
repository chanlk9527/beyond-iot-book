package com.nebula.iot.core.identity;

import com.nebula.iot.core.internal.Checks;

/** Opaque identifier. Construction does not authenticate or authorize its holder. */
public record CommandId(String value) {
    public CommandId {
        value = Checks.nonBlank(value, "CommandId");
    }
}
