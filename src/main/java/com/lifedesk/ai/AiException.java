package com.lifedesk.ai;

/** AI provider/backend failure (connection, HTTP error, bad response). */
public class AiException extends com.lifedesk.common.AppException {
    public AiException(String message) {
        super(message);
    }

    public AiException(String message, Throwable cause) {
        super(message, cause);
    }
}
