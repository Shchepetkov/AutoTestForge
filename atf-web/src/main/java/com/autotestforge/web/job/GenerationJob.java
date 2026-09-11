package com.autotestforge.web.job;

import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.TestGenerationReport;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable state of one asynchronous generation run, safe for concurrent
 * updates (pipeline threads) and reads (HTTP polling).
 */
public class GenerationJob {

    public enum State {
        RUNNING, COMPLETED, FAILED, CANCELLED;

        public boolean isTerminal() {
            return this != RUNNING;
        }
    }

    private final String id;
    private final String projectPath;
    private final Instant createdAt = Instant.now();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<ClassGenerationResult> results = new CopyOnWriteArrayList<>();
    private final AtomicInteger processed = new AtomicInteger();
    private volatile State state = State.RUNNING;
    private volatile TestGenerationReport report;
    private volatile String error;
    private volatile int totalClasses;
    private volatile Instant finishedAt;
    private volatile boolean cancelRequested;
    private volatile Future<?> handle;

    public GenerationJob(String id, String projectPath) {
        this.id = id;
        this.projectPath = projectPath;
    }

    public void addEvent(String event) {
        events.add(timestamp() + " " + event);
    }

    public void addResult(ClassGenerationResult result) {
        results.add(result);
        processed.incrementAndGet();
    }

    public void complete(TestGenerationReport report) {
        this.report = report;
        this.finishedAt = Instant.now();
        this.state = cancelRequested ? State.CANCELLED : State.COMPLETED;
    }

    public void fail(String error) {
        this.error = error;
        this.finishedAt = Instant.now();
        this.state = cancelRequested ? State.CANCELLED : State.FAILED;
    }

    /** Requests cooperative cancellation; classes already in flight finish, the rest are not started. */
    public boolean cancel() {
        if (state.isTerminal()) {
            return false;
        }
        cancelRequested = true;
        addEvent("Cancellation requested");
        Future<?> current = handle;
        if (current != null) {
            current.cancel(true);
        }
        return true;
    }

    void attach(Future<?> handle) {
        this.handle = handle;
    }

    private static String timestamp() {
        Instant now = Instant.now();
        return java.time.LocalTime.ofInstant(now, java.time.ZoneId.systemDefault())
                .withNano(0)
                .toString();
    }

    public String getId() {
        return id;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public List<String> getEvents() {
        return List.copyOf(events);
    }

    public List<ClassGenerationResult> getResults() {
        return List.copyOf(results);
    }

    public State getState() {
        return state;
    }

    public TestGenerationReport getReport() {
        return report;
    }

    public String getError() {
        return error;
    }

    public int getTotalClasses() {
        return totalClasses;
    }

    public int getProcessed() {
        return processed.get();
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void setTotalClasses(int totalClasses) {
        this.totalClasses = totalClasses;
    }
}
