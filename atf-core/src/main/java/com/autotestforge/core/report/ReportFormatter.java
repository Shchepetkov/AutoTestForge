package com.autotestforge.core.report;

import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.TestGenerationReport;

import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;

/**
 * Renders a {@link TestGenerationReport} as machine-readable JSON (for CI
 * pipelines and dashboards) or as Markdown (for pull-request comments and
 * job summaries). Hand-rolled on purpose: the core stays dependency-free.
 */
public final class ReportFormatter {

    private static final String INDENT = "  ";

    private ReportFormatter() {
    }

    public static String toJson(TestGenerationReport report) {
        return toJson(report, false);
    }

    /**
     * @param includeSources when true every result carries the generated test source
     */
    public static String toJson(TestGenerationReport report, boolean includeSources) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        field(json, 1, "projectPath", report.projectPath().toString()).append(",\n");
        field(json, 1, "startedAt", DateTimeFormatter.ISO_INSTANT.format(report.startedAt())).append(",\n");
        field(json, 1, "durationSeconds", report.duration().toSeconds()).append(",\n");
        json.append(INDENT).append("\"summary\": {\n");
        field(json, 2, "processed", report.results().size()).append(",\n");
        field(json, 2, "succeeded", report.succeeded()).append(",\n");
        field(json, 2, "skipped", report.skipped()).append(",\n");
        field(json, 2, "failed", report.failed()).append(",\n");
        field(json, 2, "llmCalls", report.totalLlmCalls()).append("\n");
        json.append(INDENT).append("},\n");
        json.append(INDENT).append("\"results\": [");
        Iterator<ClassGenerationResult> iterator = report.results().iterator();
        if (iterator.hasNext()) {
            json.append('\n');
        }
        while (iterator.hasNext()) {
            ClassGenerationResult result = iterator.next();
            json.append(INDENT).append(INDENT).append("{\n");
            field(json, 3, "class", result.classFqn()).append(",\n");
            field(json, 3, "testClass", result.testClassFqn()).append(",\n");
            field(json, 3, "status", result.status().name()).append(",\n");
            field(json, 3, "success", result.isSuccess()).append(",\n");
            field(json, 3, "writtenPath", result.writtenPath() == null ? null : result.writtenPath().toString())
                    .append(",\n");
            field(json, 3, "llmAttempts", result.llmAttempts()).append(",\n");
            field(json, 3, "error", result.errorMessage());
            if (includeSources) {
                json.append(",\n");
                field(json, 3, "testSource", result.testSource());
            }
            json.append('\n').append(INDENT).append(INDENT).append('}');
            json.append(iterator.hasNext() ? ",\n" : "\n").append(iterator.hasNext() ? "" : INDENT);
        }
        json.append("]\n}\n");
        return json.toString();
    }

    public static String toMarkdown(TestGenerationReport report) {
        StringBuilder md = new StringBuilder();
        md.append("# AutoTestForge report\n\n");
        md.append("- **Project:** `").append(report.projectPath()).append("`\n");
        md.append("- **Started:** ").append(DateTimeFormatter.ISO_INSTANT.format(report.startedAt())).append('\n');
        md.append("- **Duration:** ").append(report.duration().toSeconds()).append(" s\n");
        md.append("- **Summary:** ").append(report.summary()).append("\n\n");
        md.append("| Class | Status | LLM calls | Details |\n");
        md.append("|---|---|---:|---|\n");
        for (ClassGenerationResult result : report.results()) {
            md.append("| `").append(result.classFqn()).append("` | ")
                    .append(statusBadge(result)).append(' ').append(result.status()).append(" | ")
                    .append(result.llmAttempts()).append(" | ")
                    .append(escapeCell(details(result))).append(" |\n");
        }
        List<ClassGenerationResult> failures = report.results().stream()
                .filter(result -> result.status().isFailure())
                .toList();
        if (!failures.isEmpty()) {
            md.append("\n## Failures\n");
            for (ClassGenerationResult failure : failures) {
                md.append("\n### `").append(failure.classFqn()).append("`\n\n```\n")
                        .append(failure.errorMessage() == null ? "" : failure.errorMessage()).append("\n```\n");
            }
        }
        return md.toString();
    }

    private static String statusBadge(ClassGenerationResult result) {
        if (result.status().isFailure()) {
            return "❌";
        }
        return result.isSkipped() ? "⏭️" : "✅";
    }

    private static String details(ClassGenerationResult result) {
        if (result.errorMessage() != null) {
            return firstLine(result.errorMessage());
        }
        if (result.isSkipped()) {
            return "existing test kept: " + result.writtenPath();
        }
        return result.writtenPath() == null ? "-" : result.writtenPath().toString();
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    private static String escapeCell(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private static StringBuilder field(StringBuilder json, int depth, String name, Object value) {
        json.append(INDENT.repeat(depth)).append('"').append(name).append("\": ");
        if (value == null) {
            json.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else {
            json.append('"').append(escapeJson(value.toString())).append('"');
        }
        return json;
    }

    static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
