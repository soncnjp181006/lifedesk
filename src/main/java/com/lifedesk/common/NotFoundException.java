package com.lifedesk.common;

/** Thrown when a referenced record does not exist. */
public class NotFoundException extends AppException {
    public NotFoundException(String message) {
        super(message);
    }
}
