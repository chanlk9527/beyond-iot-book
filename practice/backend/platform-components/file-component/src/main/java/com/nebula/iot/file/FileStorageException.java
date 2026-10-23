package com.nebula.iot.file;

/** Does not expose endpoints, signed URLs or provider credentials in the public message. */
public final class FileStorageException extends RuntimeException {
    public FileStorageException(String operation,Throwable cause) { super("Object storage "+operation+" failed",cause); }
}
