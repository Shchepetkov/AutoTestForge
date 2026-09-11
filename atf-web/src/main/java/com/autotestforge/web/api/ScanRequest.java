package com.autotestforge.web.api;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Body of {@code POST /api/scan}: preview which classes a generation run with
 * the same filters would process.
 */
public record ScanRequest(
        @NotBlank(message = "projectPath is required") String projectPath,
        List<String> classes,
        List<String> excludes) {
}
