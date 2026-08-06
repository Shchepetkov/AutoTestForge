package com.autotestforge.core.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Per-run external context source definition supplied by a driving adapter. */
public record ExternalContextSourceRequest(
        String name,
        boolean enabled,
        String command,
        List<String> args,
        String toolName,
        String queryArgument,
        String queryTemplate,
        Map<String, String> arguments,
        Duration timeout,
        int maxChars) {

    public ExternalContextSourceRequest {
        name = normalize(name, "mcp").toLowerCase();
        command = normalize(command, "");
        args = args == null ? List.of() : List.copyOf(args);
        toolName = normalize(toolName, "");
        queryArgument = normalize(queryArgument, "query");
        queryTemplate = normalize(queryTemplate, null);
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        maxChars = maxChars <= 0 ? 8_000 : maxChars;
    }

    public boolean configured() {
        return enabled && !command.isBlank() && !toolName.isBlank();
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
