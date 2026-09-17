package com.autotestforge.web.api;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Body of {@code POST /api/generation}.
 *
 * @param projectPath root of the target project on the server's filesystem
 * @param classes     optional class-name filter
 * @param llm         optional provider override (ollama / openai / compatible / offline)
 * @param validate    run generated tests in isolation and self-correct
 * @param dryRun      generate without modifying the target project
 * @param context     fetch business/TMS context from configured MCP sources
 * @param contextSources optional MCP context source filter
 * @param contextQuery optional MCP search query template override
 * @param mcpSources optional per-run MCP sources from the web form
 * @param contextFiles optional uploaded business/TMS files read by the browser
 */
public record StartGenerationRequest(
        @NotBlank(message = "projectPath is required") String projectPath,
        List<String> classes,
        String llm,
        boolean validate,
        boolean dryRun,
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
            int maxChars) {
    }

    public record ContextFileRequest(
            String name,
            String type,
            String content) {
    }
}
