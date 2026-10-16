package com.nebula.iot.core.command;

/** Device-reported execution stages. A waiting timeout is not a device execution result. */
public enum ExecutionStatus {
    ACCEPTED, SUCCEEDED, FAILED
}
