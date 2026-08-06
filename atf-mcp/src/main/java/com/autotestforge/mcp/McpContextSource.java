package com.autotestforge.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Configuration for one stdio MCP context source, such as Confluence or Zephyr. */
public record McpContextSource(
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

    public McpContextSource {
        name = normalize(name, "mcp").toLowerCase();
        command = normalize(command, "");
        args = args == null ? List.of() : List.copyOf(args);
        toolName = normalize(toolName, "");
        queryArgument = normalize(queryArgument, "query");
        queryTemplate = normalize(queryTemplate, McpQueryTemplate.DEFAULT_TEMPLATE);
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        maxChars = maxChars <= 0 ? 8_000 : maxChars;
    }

    public boolean configured() {
        return enabled && !command.isBlank() && !toolName.isBlank();
    }

    public List<String> commandLine() {
        return args.isEmpty()
                ? List.of(command)
                : java.util.stream.Stream.concat(java.util.stream.Stream.of(command), args.stream()).toList();
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
