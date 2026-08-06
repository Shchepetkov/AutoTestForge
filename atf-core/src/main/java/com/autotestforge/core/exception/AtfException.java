package com.autotestforge.core.exception;

/**
 * Base of the AutoTestForge exception hierarchy. Adapters translate low-level
 * errors into subclasses so the core can handle them uniformly: a failure for
 * one class never aborts the whole run.
 */
public class AtfException extends RuntimeException {

    public AtfException(String message) {
        super(message);
    }

    public AtfException(String message, Throwable cause) {
        super(message, cause);
    }
}
