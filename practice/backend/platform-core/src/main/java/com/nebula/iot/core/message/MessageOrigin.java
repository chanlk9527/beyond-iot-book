package com.nebula.iot.core.message;

/** Assigned by the trusted ingress path, never copied blindly from a device payload. */
public enum MessageOrigin {
    DEVICE, GATEWAY
}
