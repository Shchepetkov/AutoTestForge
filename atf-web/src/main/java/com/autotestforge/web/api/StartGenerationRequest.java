package com.autotestforge.web.api;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Body of {@code POST /api/generation}.
 *
 * @param projectPath root of the target project on the server's filesystem
 * @param classes     optional class-name filter
 * @param llm         optional provider override (ollama / openai / offline)
 * @param validate    run generated tests in isolation and self-correct
 * @param dryRun      generate without modifying the target project
 */
public record StartGenerationRequest(
        @NotBlank(message = "projectPath is required") String projectPath,
        List<String> classes,
        String llm,
        boolean validate,
        boolean dryRun) {
}
