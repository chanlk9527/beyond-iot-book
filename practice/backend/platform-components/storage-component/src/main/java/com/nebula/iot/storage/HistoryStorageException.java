package com.nebula.iot.storage;

/** Public message never contains credentials, JDBC URLs or raw server errors. */
public final class HistoryStorageException extends RuntimeException {
    public HistoryStorageException(Throwable cause) { super("History storage is unavailable", cause); }
}
