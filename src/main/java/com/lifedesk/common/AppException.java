package com.lifedesk.common;

/** Base runtime exception for LifeDesk application errors. */
public class AppException extends RuntimeException {
    public AppException(String message) {
        super(message);
    }

    public AppException(String message, Throwable cause) {
        super(message, cause);
    }
}
