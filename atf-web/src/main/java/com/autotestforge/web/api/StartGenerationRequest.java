package com.autotestforge.web.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Body of {@code POST /api/generation}.
 *
 * @param projectPath    root of the target project on the server's filesystem
 * @param classes        optional class-name filter (simple / fully qualified names, wildcards)
 * @param excludes       optional classes to leave out (same syntax)
 * @param llm            optional provider override (ollama / openai / anthropic / offline)
 * @param validate       run generated tests in isolation and self-correct
 * @param dryRun         generate without modifying the target project
 * @param overwrite      regenerate classes that already have a test (null = configured default)
 * @param maxFixAttempts self-correction rounds per class (null = configured default)
 * @param parallelism    classes processed concurrently (null = configured default)
 * @param outputDir      write tests under this directory instead of the project (optional)
 * @param context        fetch business/TMS context from configured MCP sources
 * @param contextSources optional MCP context source filter
 * @param contextQuery   optional MCP search query template override
 * @param mcpSources     optional per-run MCP sources from the web form
 * @param contextFiles   optional uploaded business/TMS files read by the browser
 */
public record StartGenerationRequest(
        @NotBlank(message = "projectPath is required") String projectPath,
        List<String> classes,
        List<String> excludes,
        String llm,
        boolean validate,
        boolean dryRun,
        Boolean overwrite,
        @Min(0) @Max(10) Integer maxFixAttempts,
        @Min(1) @Max(16) Integer parallelism,
        String outputDir,
        boolean context,
        List<String> contextSources,
        String contextQuery,
        List<McpSourceRequest> mcpSources,
        List<ContextFileRequest> contextFiles) {

    public record McpSourceRequest(
            String name,
            boolean enabled,
            String command,
            List<String> args,
            String toolName,
            String queryArgument,
            String queryTemplate,
            Integer timeoutSeconds,
            int maxChars) {
    }

    public record ContextFileRequest(
            String name,
            String type,
            String content) {
    }
}
