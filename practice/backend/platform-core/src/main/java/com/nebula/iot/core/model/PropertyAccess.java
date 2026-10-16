package com.nebula.iot.core.model;

public enum PropertyAccess {
    READ_ONLY, WRITE_ONLY, READ_WRITE;

    public boolean reportable() { return this != WRITE_ONLY; }
}
