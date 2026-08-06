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

    public long succeeded() {
        return results.stream().filter(ClassGenerationResult::isSuccess).count();
    }

    public long failed() {
        return results.size() - succeeded();
    }

    public String summary() {
        return "processed=%d, succeeded=%d, failed=%d, took=%ds"
                .formatted(results.size(), succeeded(), failed(), duration.toSeconds());
    }
}
