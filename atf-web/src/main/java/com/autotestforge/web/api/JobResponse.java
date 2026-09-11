package com.autotestforge.web.api;

import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.web.job.GenerationJob;

import java.time.Instant;
import java.util.List;

/** Snapshot of a generation job returned by the REST API. */
public record JobResponse(
        String id,
        String projectPath,
        String state,
        Instant createdAt,
        Instant finishedAt,
        int totalClasses,
        int processedClasses,
        boolean cancelRequested,
        List<String> events,
        List<ResultEntry> results,
        Summary summary,
        String error) {

    public static JobResponse from(GenerationJob job) {
        return from(job, true);
    }

    /** @param includeSources false for list views, where the generated sources would bloat the payload */
    public static JobResponse from(GenerationJob job, boolean includeSources) {
        return new JobResponse(
                job.getId(),
                job.getProjectPath(),
                job.getState().name(),
                job.getCreatedAt(),
                job.getFinishedAt(),
                job.getTotalClasses(),
                job.getProcessed(),
                job.isCancelRequested(),
                job.getEvents(),
                job.getResults().stream().map(result -> ResultEntry.from(result, includeSources)).toList(),
                Summary.from(job.getReport()),
                job.getError());
    }

    public record Summary(int processed, long succeeded, long skipped, long failed, int llmCalls,
                          long durationSeconds, String text) {

        static Summary from(TestGenerationReport report) {
            if (report == null) {
                return null;
            }
            return new Summary(report.results().size(), report.succeeded(), report.skipped(), report.failed(),
                    report.totalLlmCalls(), report.duration().toSeconds(), report.summary());
        }
    }

    public record ResultEntry(
            String classFqn,
            String testClassFqn,
            String status,
            boolean success,
            String writtenPath,
            int llmAttempts,
            String errorMessage,
            String testSource) {

        static ResultEntry from(ClassGenerationResult result, boolean includeSource) {
            return new ResultEntry(
                    result.classFqn(),
                    result.testClassFqn(),
                    result.status().name(),
                    result.isSuccess(),
                    result.writtenPath() == null ? null : result.writtenPath().toString(),
                    result.llmAttempts(),
                    result.errorMessage(),
                    includeSource ? result.testSource() : null);
        }
    }
}
