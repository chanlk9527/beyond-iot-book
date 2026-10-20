package com.nebula.iot.message;

@FunctionalInterface
public interface Subscription extends AutoCloseable {
    default java.util.concurrent.CompletionStage<Void> ready() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
    @Override
    void close();
}
