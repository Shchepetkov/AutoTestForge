package com.autotestforge.core.domain;

import java.util.List;

/**
 * Outcome of executing generated tests in the isolated environment.
 *
 * @param success  true when compilation and all tests passed
 * @param failures structured test failures (empty when success or when only compilation failed)
 * @param rawLog   trimmed runner output, used for LLM self-correction and diagnostics
 */
public record ValidationResult(boolean success, List<TestFailure> failures, String rawLog) {

    public static ValidationResult success(String rawLog) {
        return new ValidationResult(true, List.of(), rawLog);
    }

    public static ValidationResult failure(List<TestFailure> failures, String rawLog) {
        return new ValidationResult(false, failures, rawLog);
    }
}
