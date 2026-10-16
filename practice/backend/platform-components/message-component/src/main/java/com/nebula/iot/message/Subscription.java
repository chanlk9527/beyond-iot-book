package com.nebula.iot.message;

@FunctionalInterface
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}