package com.autotestforge.core.domain;

/**
 * Callback used by driving adapters (CLI, web) to surface pipeline progress.
 * All methods have no-op defaults so implementors override only what they need.
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

    /** Called after a class has been fully processed (successfully or not). */
    default void onClassFinished(ClassGenerationResult result) {
    }
}
