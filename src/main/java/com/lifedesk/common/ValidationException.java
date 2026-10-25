package com.lifedesk.common;

/** Thrown when user or agent input violates validation rules. */
public class ValidationException extends AppException {
    public ValidationException(String message) {
        super(message);
    }
}
