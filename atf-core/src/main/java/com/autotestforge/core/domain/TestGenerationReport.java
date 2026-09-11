package com.autotestforge.core.domain;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Aggregated outcome of a full generation run.
 */
public record TestGenerationReport(
        Path projectPath,
        List<ClassGenerationResult> results,
        Instant startedAt,
        Duration duration) {

    public TestGenerationReport {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public long succeeded() {
        return results.stream().filter(ClassGenerationResult::isSuccess).count();
    }

    public long skipped() {
        return results.stream().filter(ClassGenerationResult::isSkipped).count();
    }

    public long failed() {
        return results.stream().filter(result -> result.status().isFailure()).count();
    }

    public int totalLlmCalls() {
        return results.stream().mapToInt(ClassGenerationResult::llmAttempts).sum();
    }

    public String summary() {
        return "processed=%d, succeeded=%d, skipped=%d, failed=%d, llmCalls=%d, took=%ds"
                .formatted(results.size(), succeeded(), skipped(), failed(), totalLlmCalls(), duration.toSeconds());
    }
}
