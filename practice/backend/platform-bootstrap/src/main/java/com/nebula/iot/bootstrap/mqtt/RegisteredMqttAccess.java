package com.nebula.iot.bootstrap.mqtt;

import com.nebula.iot.core.auth.*;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.device.api.DeviceConnectionDirectory;
import com.nebula.iot.gateway.*;
import com.nebula.iot.network.mqtt.MqttAuthenticationRequest;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import java.util.concurrent.*;

/** Blocking database and BCrypt work is invoked on the gateway's bounded worker pool. */
public final class RegisteredMqttAccess implements DeviceAuthenticator, DeviceAccessResolver {
    private final ObjectProvider<DeviceConnectionDirectory> directories;
    public RegisteredMqttAccess(ObjectProvider<DeviceConnectionDirectory> directories) { this.directories = directories; }
    @Override public CompletionStage<AuthenticationResult> authenticate(DeviceAuthenticationRequest request) {
        var directory = directories.getIfAvailable();
        if (directory != null && request instanceof MqttAuthenticationRequest mqtt) {
            var password = mqtt.password();
            try {
                var key = directory.authenticate(mqtt.clientId(), mqtt.principal(), password);
                if (key.isPresent()) return CompletableFuture.completedFuture(new AuthenticationResult.Accepted(key.get()));
            } finally { Arrays.fill(password, (byte) 0); }
        }
        return CompletableFuture.completedFuture(new AuthenticationResult.Rejected("INVALID_CREDENTIALS"));
    }
    @Override public CompletionStage<Optional<DeviceAccessContext>> resolve(DeviceKey key) {
        var directory = directories.getIfAvailable();
        return CompletableFuture.completedFuture(directory == null ? Optional.empty()
                : directory.resolve(key).map(access -> new DeviceAccessContext(access.device(), access.model())));
    }
}
