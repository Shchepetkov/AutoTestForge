package com.autotestforge.core.domain;

/**
 * Callback used by driving adapters (CLI, web) to surface pipeline progress.
 * All methods have no-op defaults so implementors override only what they need.
 * When {@link TestGenerationRequest#parallelism()} is greater than one the
 * callbacks are invoked concurrently from worker threads.
 */
public interface ProgressListener {

    ProgressListener NO_OP = new ProgressListener() {
    };

    /** Called once scanning is done. */
    default void onScanCompleted(int discoveredClasses, int selectedClasses) {
    }

    /** Called before test generation starts for a class. */
    default void onClassStarted(String classFqn) {
    }

    /**
     * Called when validation failed and a self-correction round is about to start.
     *
     * @param round        1-based index of the fix round
     * @param maxRounds    configured maximum number of rounds
     * @param failureCount number of structured test failures (0 for compilation errors)
     */
    default void onSelfCorrection(String classFqn, int round, int maxRounds, int failureCount) {
    }

    /** Called after a class has been fully processed (successfully or not). */
    default void onClassFinished(ClassGenerationResult result) {
    }
}
