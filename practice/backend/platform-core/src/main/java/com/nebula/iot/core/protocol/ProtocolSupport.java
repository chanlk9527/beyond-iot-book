package com.nebula.iot.core.protocol;

import com.nebula.iot.core.auth.DeviceAuthenticator;
import java.util.Set;

/** Protocol extension SPI. Plugin discovery, lifecycle and configuration belong to components. */
public interface ProtocolSupport {
    String id();
    Set<Transport> supportedTransports();
    DeviceAuthenticator authenticator();

    /** Reject unsupported transports explicitly rather than falling back to another codec. */
    DeviceMessageCodec codec(Transport transport);
}
