package com.nebula.iot.core.auth;

import com.nebula.iot.core.identity.TenantId;
import com.nebula.iot.core.protocol.Transport;
import java.util.Optional;

/**
 * Untrusted authentication input. A protocol-specific subtype carries its own credential material,
 * with redacted logging and an appropriate lifetime. The core never stores plaintext credentials.
 */
public interface DeviceAuthenticationRequest {
    Transport transport();
    String principal();
    Optional<TenantId> tenantHint();
}
