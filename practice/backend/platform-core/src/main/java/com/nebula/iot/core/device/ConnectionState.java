package com.nebula.iot.core.device;

/** Observed connectivity; ONLINE alone does not prove availability or controllability. */
public enum ConnectionState {
    ONLINE, OFFLINE, UNKNOWN
}
