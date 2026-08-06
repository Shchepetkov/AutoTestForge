package com.autotestforge.core.exception;

/** Failure of the validation infrastructure itself (Docker unavailable, timeout, ...), not of the tests. */
public class ValidationException extends AtfException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
