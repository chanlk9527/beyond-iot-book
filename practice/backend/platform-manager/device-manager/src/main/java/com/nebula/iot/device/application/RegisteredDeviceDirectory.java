package com.nebula.iot.device.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.core.device.*;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.model.DeviceModel;
import com.nebula.iot.device.api.CatalogViews.ModelSpec;
import com.nebula.iot.device.api.DeviceConnectionDirectory;
import com.nebula.iot.device.domain.CatalogRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.event.TransactionalEventListener;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Optional;

@Transactional(readOnly = true)
public class RegisteredDeviceDirectory implements DeviceConnectionDirectory {
    private final CatalogRepository data;
    private final PasswordEncoder encoder;
    private final ObjectMapper json;
    private final DeviceConnectionCache cache;
    public RegisteredDeviceDirectory(CatalogRepository data, PasswordEncoder encoder, ObjectMapper json,
            DeviceConnectionCache cache) {
        this.data = data; this.encoder = encoder; this.json = json; this.cache = cache;
    }
    @Override public Optional<DeviceKey> authenticate(String clientId, String username, byte[] password) {
        if (clientId == null || !clientId.equals(username) || clientId.length() > 36
                || password == null || password.length == 0 || password.length > 72) return Optional.empty();
        var row = cache.get(clientId);
        if (row == null) return Optional.empty();
        try {
            String supplied = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(password)).toString();
            return encoder.matches(supplied, row.credentialHash())
                    ? Optional.of(new DeviceKey(new TenantId(row.scopeKey()), new DeviceId(row.id()))) : Optional.empty();
        } catch (CharacterCodingException invalid) { return Optional.empty(); }
    }
    @Override public Optional<Access> resolve(DeviceKey key) {
        var row = cache.get(key.deviceId().value());
        if (row == null || !row.scopeKey().equals(key.tenantId().value())) return Optional.empty();
        try {
            var spec = json.readValue(row.modelJson(), ModelSpec.class);
            var product = new ProductKey(key.tenantId(), new ProductId(row.productId()));
            return Optional.of(new Access(new DeviceDescriptor(key, product, row.modelVersion(), DeviceLifecycle.ACTIVE),
                    new DeviceModel(product, row.modelVersion(), spec.properties(), spec.events(), spec.operations())));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid published device model", invalid);
        }
    }

    /** Called after a device/model eligibility change commits. */
    public void invalidate(String deviceId) { cache.invalidate(deviceId); }
    public void invalidateAll() { cache.invalidateAll(); }
    public com.nebula.iot.device.infrastructure.cache.ThreeLevelCache.CacheStats cacheStats() { return cache.stats(); }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @TransactionalEventListener public void onCacheInvalidated(com.nebula.iot.device.api.DeviceConnectionDirectory.CacheInvalidated event) {
        if (event.all()) invalidateAll(); else invalidate(event.deviceId());
    }
}
