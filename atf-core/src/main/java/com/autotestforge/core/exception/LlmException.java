package com.autotestforge.core.exception;

/** Failure while calling the LLM or parsing its response. */
public class LlmException extends AtfException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
