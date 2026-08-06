package com.autotestforge.core.exception;

/** Failure while writing generated tests or updating build files of the target project. */
public class TestWriteException extends AtfException {

    public TestWriteException(String message) {
        super(message);
    }

    public TestWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
