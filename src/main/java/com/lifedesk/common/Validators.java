package com.lifedesk.common;

/** Small argument validation helpers used across services. */
public final class Validators {
    private Validators() {
    }

    public static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " không được để trống");
        }
        return value.trim();
    }

    public static void requireTrue(boolean condition, String message) {
        if (!condition) {
            throw new ValidationException(message);
        }
    }

    public static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new ValidationException(field + " không được để trống");
        }
        return value;
    }
}
