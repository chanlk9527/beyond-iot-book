package com.nebula.iot.core.auth;

import java.util.concurrent.CompletionStage;

public interface DeviceAuthenticator {
    /** Invalid proof returns Rejected; infrastructure failures complete exceptionally. */
    CompletionStage<AuthenticationResult> authenticate(DeviceAuthenticationRequest request);
}
