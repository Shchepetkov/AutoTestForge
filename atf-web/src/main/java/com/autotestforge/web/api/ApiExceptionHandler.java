package com.autotestforge.web.api;

import com.autotestforge.web.llm.LlmDiagnosticsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts rejected paths and invalid tool arguments into stable HTTP 400 responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LlmDiagnosticsService.ConnectionException.class)
    public ProblemDetail connectionFailed(LlmDiagnosticsService.ConnectionException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, exception.getMessage());
        detail.setTitle("LLM connection failed");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setTitle("Invalid request");
        return detail;
    }
}
