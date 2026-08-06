package com.autotestforge.core.domain;

import java.util.Objects;

/**
 * A business, requirements or test-management fragment retrieved from an
 * external system before generating tests for a class.
 */
public record ExternalContextSnippet(String source, String title, String content) {

    public ExternalContextSnippet {
        source = normalize(source, "external");
        title = normalize(title, "Untitled");
        content = normalize(content, "");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }

    private static String normalize(String value, String fallback) {
        return Objects.toString(value, fallback).strip();
    }
}
