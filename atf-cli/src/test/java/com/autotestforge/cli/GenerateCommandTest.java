package com.autotestforge.cli;

import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.GenerationStatus;
import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.exception.ScanException;
import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.spring.AtfProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateCommandTest {

    @TempDir
    Path tempDir;

    private final GenerateTestsUseCase useCase = mock(GenerateTestsUseCase.class);
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private GenerateCommand command;

    @BeforeEach
    void setUp() {
        AtfProperties properties = new AtfProperties(null, new AtfProperties.Generation(2, false),
                new AtfProperties.Validation(3, true, "maven", "gradle", Duration.ofMinutes(1), tempDir), null);
        command = new GenerateCommand(useCase, properties,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("flags are mapped onto the request; configuration supplies the defaults")
    void call_shouldBuildRequestFromFlags() {
        when(useCase.generateTests(any())).thenReturn(report(
                new ClassGenerationResult("com.acme.A", "com.acme.ATest", GenerationStatus.WRITTEN,
                        tempDir.resolve("ATest.java"), 1, null, "class ATest {}")));

        int exitCode = new CommandLine(command).execute(
                "-p", tempDir.toString(),
                "-c", "com.acme.*,*Service", "-x", "Legacy*",
                "--llm", "openai", "--validate", "--overwrite", "-j", "4",
                "--with-external-context", "--context-sources", "confluence,zephyr");

        assertThat(exitCode).isZero();
        ArgumentCaptor<TestGenerationRequest> captor = ArgumentCaptor.forClass(TestGenerationRequest.class);
        verify(useCase).generateTests(captor.capture());
        TestGenerationRequest request = captor.getValue();
        assertThat(request.projectPath()).isEqualTo(tempDir.toAbsolutePath().normalize());
        assertThat(request.includedClasses()).containsExactly("com.acme.*", "*Service");
        assertThat(request.excludedClasses()).containsExactly("Legacy*");
        assertThat(request.llmProvider()).isEqualTo("openai");
        assertThat(request.validate()).isTrue();
        assertThat(request.overwriteExisting()).isTrue();
        assertThat(request.parallelism()).isEqualTo(4);
        assertThat(request.maxFixAttempts()).isEqualTo(3);
        assertThat(request.externalContext().enabled()).isTrue();
        assertThat(request.externalContext().sources()).containsExactly("confluence", "zephyr");
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("com.acme.A")
                .contains("WRITTEN")
                .contains("processed=1, succeeded=1");
    }

    @Test
    @DisplayName("configuration defaults apply when flags are absent")
    void call_shouldUseConfiguredDefaults() {
        when(useCase.generateTests(any())).thenReturn(report());

        new CommandLine(command).execute("-p", tempDir.toString());

        ArgumentCaptor<TestGenerationRequest> captor = ArgumentCaptor.forClass(TestGenerationRequest.class);
        verify(useCase).generateTests(captor.capture());
        assertThat(captor.getValue().parallelism()).isEqualTo(2);
        assertThat(captor.getValue().overwriteExisting()).isFalse();
        assertThat(captor.getValue().maxFixAttempts()).isEqualTo(3);
        assertThat(captor.getValue().externalContext().enabled()).isFalse();
    }

    @Test
    @DisplayName("exit code 1 when a class failed, skipped classes do not count as failures")
    void call_shouldReturnOne_whenAnyClassFailed() {
        when(useCase.generateTests(any())).thenReturn(report(
                ClassGenerationResult.skipped("com.acme.A", "com.acme.ATest", tempDir.resolve("ATest.java")),
                ClassGenerationResult.failed("com.acme.B", 2, "model unreachable\nmore details")));

        int exitCode = new CommandLine(command).execute("-p", tempDir.toString());

        assertThat(exitCode).isEqualTo(1);
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .contains("SKIPPED")
                .contains("existing test kept")
                .contains("model unreachable")
                .doesNotContain("more details");
    }

    @Test
    @DisplayName("reports are written to the requested files and sources printed on demand")
    void call_shouldWriteReportsAndPrintSources() throws Exception {
        when(useCase.generateTests(any())).thenReturn(report(
                new ClassGenerationResult("com.acme.A", "com.acme.ATest", GenerationStatus.GENERATED,
                        null, 1, null, "class ATest { /* generated */ }")));
        Path json = tempDir.resolve("reports/report.json");
        Path markdown = tempDir.resolve("reports/report.md");

        int exitCode = new CommandLine(command).execute("-p", tempDir.toString(), "--dry-run", "--print",
                "--report-json", json.toString(), "--report-markdown", markdown.toString());

        assertThat(exitCode).isZero();
        assertThat(Files.readString(json)).contains("\"status\": \"GENERATED\"").contains("/* generated */");
        assertThat(Files.readString(markdown)).contains("| `com.acme.A` |");
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("// ---- com.acme.ATest (GENERATED) ----")
                .contains("class ATest { /* generated */ }");
    }

    @Test
    @DisplayName("pipeline-level errors produce exit code 2 and a message on stderr")
    void call_shouldReturnTwo_whenRunCannotStart() {
        when(useCase.generateTests(any())).thenThrow(new ScanException("No src/main/java source roots found"));

        int exitCode = new CommandLine(command).execute("-p", tempDir.toString());

        assertThat(exitCode).isEqualTo(2);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("No src/main/java");
    }

    @Test
    @DisplayName("an invalid parallelism value is reported instead of crashing")
    void call_shouldRejectInvalidParallelism() {
        int exitCode = new CommandLine(command).execute("-p", tempDir.toString(), "-j", "99");

        assertThat(exitCode).isEqualTo(2);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("parallelism");
    }

    private TestGenerationReport report(ClassGenerationResult... results) {
        return new TestGenerationReport(tempDir, List.of(results), Instant.now(), Duration.ofSeconds(1));
    }
}
