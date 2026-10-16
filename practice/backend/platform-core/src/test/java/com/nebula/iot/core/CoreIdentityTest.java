package com.nebula.iot.core;

import com.nebula.iot.core.device.*;
import com.nebula.iot.core.identity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CoreIdentityTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", " device", "device ", "dev\nice", "dev\u0000ice"})
    void invalidIdentifiersAreRejectedWithoutSilentNormalization(String id) {
        assertThrows(IllegalArgumentException.class, () -> new DeviceId(id));
        assertThrows(IllegalArgumentException.class, () -> new TenantId(id));
        assertThrows(IllegalArgumentException.class, () -> new ProductId(id));
        assertThrows(IllegalArgumentException.class, () -> new MessageId(id));
        assertThrows(IllegalArgumentException.class, () -> new CommandId(id));
    }

    @Test
    void sameLocalDeviceIdInDifferentTenantsIsNotTheSameDevice() {
        var id = new DeviceId("warmer-1");
        assertNotEquals(new DeviceKey(new TenantId("a"), id), new DeviceKey(new TenantId("b"), id));
        assertNotEquals(new DeviceId("Warmer-1"), id);
    }

    @Test
    void deviceCannotReferenceAnotherTenantsProduct() {
        var device = new DeviceKey(new TenantId("a"), new DeviceId("warmer-1"));
        var product = new ProductKey(new TenantId("b"), new ProductId("warmer"));
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceDescriptor(device, product, "v1", DeviceLifecycle.ACTIVE));
    }
}
