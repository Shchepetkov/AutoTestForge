package com.autotestforge.core.report;

import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.GenerationStatus;
import com.autotestforge.core.domain.TestGenerationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportFormatterTest {

    private final TestGenerationReport report = new TestGenerationReport(
            Path.of("/repo"),
            List.of(
                    new ClassGenerationResult("com.acme.OrderService", "com.acme.OrderServiceTest",
                            GenerationStatus.VALIDATED, Path.of("/repo/src/test/java/com/acme/OrderServiceTest.java"),
                            1, null, "class OrderServiceTest {\n  // \"quoted\"\n}"),
                    ClassGenerationResult.skipped("com.acme.TextUtils", "com.acme.TextUtilsTest",
                            Path.of("/repo/src/test/java/com/acme/TextUtilsTest.java")),
                    ClassGenerationResult.failed("com.acme.Broken", 3, "Still failing: expected | got\nsecond line")),
            Instant.parse("2026-01-02T03:04:05Z"),
            Duration.ofSeconds(42));

    @Test
    @DisplayName("JSON output is well-formed, escapes strings and carries the summary")
    void toJson_shouldRenderReport() {
        String json = ReportFormatter.toJson(report, true);

        assertThat(json)
                .startsWith("{")
                .contains("\"projectPath\": \"/repo\"")
                .contains("\"startedAt\": \"2026-01-02T03:04:05Z\"")
                .contains("\"durationSeconds\": 42")
                .contains("\"processed\": 3")
                .contains("\"succeeded\": 1")
                .contains("\"skipped\": 1")
                .contains("\"failed\": 1")
                .contains("\"llmCalls\": 4")
                .contains("\"status\": \"SKIPPED\"")
                .contains("\"error\": \"Still failing: expected | got\\nsecond line\"")
                .contains("\"testSource\": \"class OrderServiceTest {\\n  // \\\"quoted\\\"\\n}\"")
                .endsWith("]\n}\n");
        assertThat(ReportFormatter.toJson(report)).doesNotContain("testSource");
        assertThat(ReportFormatter.toJson(new TestGenerationReport(Path.of("/x"), List.of(),
                Instant.EPOCH, Duration.ZERO))).contains("\"results\": []");
    }

    @Test
    @DisplayName("Markdown output has a table row per class and a failure section")
    void toMarkdown_shouldRenderTableAndFailures() {
        String markdown = ReportFormatter.toMarkdown(report);

        assertThat(markdown)
                .startsWith("# AutoTestForge report")
                .contains("| Class | Status | LLM calls | Details |")
                .contains("| `com.acme.OrderService` | ✅ VALIDATED | 1 |")
                .contains("| `com.acme.TextUtils` | ⏭️ SKIPPED | 0 | existing test kept:")
                .contains("| `com.acme.Broken` | ❌ FAILED | 3 | Still failing: expected \\| got |")
                .contains("## Failures")
                .contains("### `com.acme.Broken`");
    }

    @Test
    @DisplayName("control characters are escaped for JSON")
    void escapeJson_shouldEscapeControlCharacters() {
        assertThat(ReportFormatter.escapeJson("a\tb\u0001c\\d")).isEqualTo("a\\tb\\u0001c\\\\d");
    }
}
