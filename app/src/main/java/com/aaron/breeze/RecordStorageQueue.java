package com.aaron.breeze;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** FIFO storage work. Completion is dispatched only after the durable operation returns. */
final class RecordStorageQueue {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Breeze-record-storage");
        thread.setDaemon(true);
        return thread;
    });
    private final Consumer<Runnable> dispatcher;
    private boolean closed;

    RecordStorageQueue(Consumer<Runnable> dispatcher) { this.dispatcher = dispatcher; }

    synchronized <T> boolean submit(Callable<T> operation, Consumer<T> completed, Consumer<Exception> failed) {
        if (closed) return false;
        worker.execute(() -> {
            T result;
            try { result = operation.call(); }
            catch (Exception error) { dispatcher.accept(() -> failed.accept(error)); return; }
            dispatcher.accept(() -> completed.accept(result));
        });
        return true;
    }

    /** Never interrupt a commit or close its database while earlier accepted work is pending. */
    synchronized void close(Runnable closeStorage) {
        if (closed) return;
        closed = true;
        worker.execute(closeStorage);
        worker.shutdown();
    }
}
