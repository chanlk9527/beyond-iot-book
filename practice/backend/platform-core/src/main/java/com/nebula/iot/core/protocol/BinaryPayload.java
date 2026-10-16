package com.nebula.iot.core.protocol;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/** Immutable byte payload; toString never prints protocol contents or credentials. */
public final class BinaryPayload {
    private final byte[] bytes;

    public BinaryPayload(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    public byte[] copyBytes() { return bytes.clone(); }
    public ByteBuffer asReadOnlyBuffer() { return ByteBuffer.wrap(bytes).asReadOnlyBuffer(); }
    public int size() { return bytes.length; }

    @Override public boolean equals(Object other) {
        return other instanceof BinaryPayload payload && Arrays.equals(bytes, payload.bytes);
    }
    @Override public int hashCode() { return Arrays.hashCode(bytes); }
    @Override public String toString() { return "BinaryPayload[size=" + bytes.length + "]"; }
}
