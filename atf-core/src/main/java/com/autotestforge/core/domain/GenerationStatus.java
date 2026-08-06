package com.autotestforge.core.domain;

/** Final state of test generation for a single class. */
public enum GenerationStatus {
    /** Test generated but not written (dry-run mode). */
    GENERATED,
    /** Test written to the target project; validation was not requested. */
    WRITTEN,
    /** Test written and passed validation on the first attempt. */
    VALIDATED,
    /** Test initially failed validation but passed after LLM self-correction. */
    FIXED_AND_VALIDATED,
    /** Test written but still failing after exhausting all fix attempts. */
    VALIDATION_FAILED,
    /** Pipeline error for this class (scan, LLM, parsing or I/O). */
    FAILED
}
