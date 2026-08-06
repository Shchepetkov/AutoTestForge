package com.autotestforge.core.exception;

/** Failure while scanning or parsing the target project sources. */
public class ScanException extends AtfException {

    public ScanException(String message) {
        super(message);
    }

    public ScanException(String message, Throwable cause) {
        super(message, cause);
    }
}
