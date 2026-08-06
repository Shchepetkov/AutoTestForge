package com.autotestforge.core.domain;

/**
 * One failing test case extracted from the runner report; fed back to the LLM
 * during the self-correction loop.
 *
 * @param testClass  fully qualified test class name
 * @param testMethod failing test method
 * @param message    assertion / error message
 * @param detail     stack trace or additional output (possibly truncated)
 */
public record TestFailure(String testClass, String testMethod, String message, String detail) {

    public String describe() {
        return testClass + "#" + testMethod + ": " + message
                + (detail == null || detail.isBlank() ? "" : System.lineSeparator() + detail);
    }
}
