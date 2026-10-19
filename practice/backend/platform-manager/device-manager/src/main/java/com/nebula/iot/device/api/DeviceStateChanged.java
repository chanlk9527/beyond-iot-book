package com.nebula.iot.device.api;

import com.nebula.iot.core.device.DeviceKey;
/** Published only after the state store successfully commits. No device credentials or raw payload. */
public record DeviceStateChanged(DeviceKey device, String modelVersion, long version) { }
