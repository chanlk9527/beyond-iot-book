package com.nebula.iot.core.auth;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.internal.Checks;
import java.util.Objects;

/** Authentication establishes device identity, not user or application resource permissions. */
public sealed interface AuthenticationResult {
    record Accepted(DeviceKey device) implements AuthenticationResult {
        public Accepted { Objects.requireNonNull(device, "device"); }
    }

    /** Stable failure code, without credential contents or exception messages. */
    record Rejected(String code) implements AuthenticationResult {
        public Rejected { code = Checks.nonBlank(code, "code"); }
    }
}
