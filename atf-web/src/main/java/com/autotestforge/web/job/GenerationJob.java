package com.autotestforge.web.job;

import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.TestGenerationReport;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mutable state of one asynchronous generation run, safe for concurrent
 * updates (pipeline thread) and reads (HTTP polling).
 */
public class GenerationJob {

    public enum State {RUNNING, COMPLETED, FAILED}

    private final String id;
    private final String projectPath;
    private final Instant createdAt = Instant.now();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<ClassGenerationResult> results = new CopyOnWriteArrayList<>();
    private volatile State state = State.RUNNING;
    private volatile TestGenerationReport report;
    private volatile String error;
    private volatile int totalClasses;

    public GenerationJob(String id, String projectPath) {
        this.id = id;
        this.projectPath = projectPath;
    }

    public void addEvent(String event) {
        events.add(event);
    }

    public void addResult(ClassGenerationResult result) {
        results.add(result);
    }

    public void complete(TestGenerationReport report) {
        this.report = report;
        this.state = State.COMPLETED;
    }

    public void fail(String error) {
        this.error = error;
        this.state = State.FAILED;
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

    public void setTotalClasses(int totalClasses) {
        this.totalClasses = totalClasses;
    }
}
