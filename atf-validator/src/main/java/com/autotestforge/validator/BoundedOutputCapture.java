package com.autotestforge.validator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * Drains a process stream on a daemon thread while keeping only the last
 * {@code maxChars} characters. Draining continuously keeps the child process
 * from blocking on a full pipe; bounding the buffer keeps a chatty build from
 * exhausting memory. The head of the output is preserved separately because
 * compilation errors appear early while test failures appear late.
 */
final class BoundedOutputCapture {

    private static final int HEAD_CHARS = 4_000;

    private final int maxChars;
    private final Deque<String> tail = new ArrayDeque<>();
    private final StringBuilder head = new StringBuilder();
    private final Thread thread;
    private int tailChars;
    private boolean truncated;

    BoundedOutputCapture(InputStream stream, int maxChars) {
        this.maxChars = Math.max(HEAD_CHARS * 2, maxChars);
        this.thread = new Thread(() -> drain(stream), "atf-process-output");
        thread.setDaemon(true);
        thread.start();
    }

    private void drain(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                append(line);
            }
        } catch (IOException ignored) {
            // the stream closes when the process ends or is destroyed
        }
    }

    private synchronized void append(String line) {
        if (head.length() < HEAD_CHARS) {
            head.append(line).append('\n');
            return;
        }
        tail.addLast(line);
        tailChars += line.length() + 1;
        while (tailChars > maxChars - HEAD_CHARS && !tail.isEmpty()) {
            tailChars -= tail.removeFirst().length() + 1;
            truncated = true;
        }
    }

    /** Waits for the stream to reach EOF (bounded) and returns the captured text. */
    String awaitOutput(long timeout, TimeUnit unit) {
        try {
            thread.join(unit.toMillis(timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return text();
    }

    synchronized String text() {
        StringBuilder result = new StringBuilder(head);
        if (truncated) {
            result.append("... [output truncated] ...\n");
        }
        tail.forEach(line -> result.append(line).append('\n'));
        return result.toString();
    }
}
