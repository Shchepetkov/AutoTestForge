package com.autotestforge.mcp;

import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

/** Renders search queries for external MCP tools from class metadata. */
public final class McpQueryTemplate {

    public static final String DEFAULT_TEMPLATE = """
            Find business rules, requirements, acceptance criteria and test cases relevant to \
            ${fullyQualifiedName}. Public methods: ${methods}.
            """;

    private McpQueryTemplate() {
    }

    public static String render(String template, JavaClassInfo classInfo, Path projectPath) {
        String resolvedTemplate = template == null || template.isBlank() ? DEFAULT_TEMPLATE : template;
        Map<String, String> values = Map.of(
                "className", classInfo.className(),
                "fullyQualifiedName", classInfo.fullyQualifiedName(),
                "packageName", classInfo.packageName(),
                "projectPath", projectPath == null ? "" : projectPath.toString(),
                "methods", methods(classInfo));
        String rendered = resolvedTemplate;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return rendered.strip();
    }

    private static String methods(JavaClassInfo classInfo) {
        return classInfo.publicMethods().stream()
                .map(MethodInfo::signature)
                .collect(Collectors.joining(", "));
    }
}
