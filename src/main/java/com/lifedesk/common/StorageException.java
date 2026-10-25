package com.lifedesk.common;

/** Thrown when reading or writing persisted data fails in a non-recoverable way. */
public class StorageException extends AppException {
    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
