package com.lifedesk.common;

import java.util.UUID;

/** Stable id generation. Ids never depend on file names. */
public final class Ids {
    private Ids() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
