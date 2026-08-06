package com.autotestforge.web.api;

import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.web.job.GenerationJob;

import java.time.Instant;
import java.util.List;

/** Snapshot of a generation job returned by the REST API. */
public record JobResponse(
        String id,
        String projectPath,
        String state,
        Instant createdAt,
        int totalClasses,
        List<String> events,
        List<ResultEntry> results,
        String summary,
        String error) {

    public static JobResponse from(GenerationJob job) {
        return new JobResponse(
                job.getId(),
                job.getProjectPath(),
                job.getState().name(),
                job.getCreatedAt(),
                job.getTotalClasses(),
                job.getEvents(),
                job.getResults().stream().map(ResultEntry::from).toList(),
                job.getReport() == null ? null : job.getReport().summary(),
                job.getError());
    }

    public record ResultEntry(
            String classFqn,
            String testClassFqn,
            String status,
            String writtenPath,
            int llmAttempts,
            String errorMessage) {

        static ResultEntry from(ClassGenerationResult result) {
            return new ResultEntry(
                    result.classFqn(),
                    result.testClassFqn(),
                    result.status().name(),
                    result.writtenPath() == null ? null : result.writtenPath().toString(),
                    result.llmAttempts(),
                    result.errorMessage());
        }
    }
}
