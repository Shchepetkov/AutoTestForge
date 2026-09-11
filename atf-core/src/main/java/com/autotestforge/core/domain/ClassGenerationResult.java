package com.autotestforge.core.domain;

import java.nio.file.Path;

/**
 * Per-class outcome collected into the {@link TestGenerationReport}.
 *
 * @param classFqn      fully qualified name of the class under test
 * @param testClassFqn  fully qualified name of the generated test, null when generation failed early
 * @param status        final status
 * @param writtenPath   where the test was written (or, for {@link GenerationStatus#SKIPPED}, the existing test);
 *                      null in dry-run or on failure
 * @param llmAttempts   number of LLM calls spent on this class (initial + fixes)
 * @param errorMessage  failure description, null on success
 * @param testSource    final source of the generated test, null when nothing was generated
 */
public record ClassGenerationResult(
        String classFqn,
        String testClassFqn,
        GenerationStatus status,
        Path writtenPath,
        int llmAttempts,
        String errorMessage,
        String testSource) {

    public static ClassGenerationResult failed(String classFqn, int llmAttempts, String errorMessage) {
        return new ClassGenerationResult(classFqn, null, GenerationStatus.FAILED, null, llmAttempts, errorMessage, null);
    }

    public static ClassGenerationResult skipped(String classFqn, String testClassFqn, Path existingTest) {
        return new ClassGenerationResult(classFqn, testClassFqn, GenerationStatus.SKIPPED, existingTest, 0, null, null);
    }

    public boolean isSuccess() {
        return !status.isFailure() && status != GenerationStatus.SKIPPED;
    }

    public boolean isSkipped() {
        return status == GenerationStatus.SKIPPED;
    }
}
