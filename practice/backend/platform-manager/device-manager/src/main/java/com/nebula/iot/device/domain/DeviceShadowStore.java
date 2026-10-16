package com.nebula.iot.device.domain;

import com.nebula.iot.core.device.DeviceKey;

public interface DeviceShadowStore {
    ShadowDocument read(DeviceKey key, String modelVersion);
    boolean compareAndSet(DeviceKey key, long expectedVersion, ShadowDocument document);
}

