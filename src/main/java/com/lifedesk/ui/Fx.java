package com.lifedesk.ui;

import javafx.application.Platform;

/** Helpers for running work on/off the JavaFX Application Thread. */
public final class Fx {
    private Fx() {
    }

    public static void runLater(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /** Run I/O work on a background executor; handle result/error on the FX thread. */
    public static <T> void async(java.util.concurrent.Executor executor,
                                 java.util.function.Supplier<T> work,
                                 java.util.function.Consumer<T> onSuccess,
                                 java.util.function.Consumer<Throwable> onError) {
        executor.execute(() -> {
            try {
                T result = work.get();
                if (onSuccess != null) {
                    runLater(() -> onSuccess.accept(result));
                }
            } catch (Throwable t) {
                if (onError != null) {
                    runLater(() -> onError.accept(t));
                } else {
                    runLater(() -> Dialogs.error("Lỗi nền", t.getMessage() == null ? t.toString() : t.getMessage()));
                }
            }
        });
    }

    public static void async(java.util.concurrent.Executor executor,
                             Runnable work,
                             Runnable onSuccess,
                             java.util.function.Consumer<Throwable> onError) {
        async(executor, () -> {
            work.run();
            return true;
        }, ok -> {
            if (onSuccess != null) {
                onSuccess.run();
            }
        }, onError);
    }
}
