package com.autotestforge.cli;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.ClassTarget;
import com.autotestforge.core.domain.ScanPreview;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.exception.ScanException;
import com.autotestforge.core.port.in.ScanProjectUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScanCommandTest {

    @TempDir
    Path tempDir;

    private final ScanProjectUseCase useCase = mock(ScanProjectUseCase.class);
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    @Test
    @DisplayName("eligible classes are listed; skipped ones only with --all")
    void call_shouldListTargets() {
        ScanPreview preview = new ScanPreview(tempDir, BuildTool.MAVEN, List.of(
                new ClassTarget("com.acme.OrderService", ClassKind.CLASS, "Service", List.of("place", "cancel"),
                        List.of("com.acme.Repo"), tempDir.resolve("OrderService.java"), true, null,
                        tempDir.resolve("OrderServiceTest.java")),
                new ClassTarget("com.acme.Repo", ClassKind.INTERFACE, null, List.of("find"), List.of(),
                        tempDir.resolve("Repo.java"), false, "interface", null)));
        when(useCase.previewTargets(any())).thenReturn(preview);

        int exitCode = new CommandLine(command()).execute("-p", tempDir.toString(), "-c", "com.acme.*");

        assertThat(exitCode).isZero();
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .contains("build tool: MAVEN")
                .contains("com.acme.OrderService")
                .contains("@Service has test (needs --overwrite)")
                .doesNotContain("com.acme.Repo ")
                .contains("2 type(s) discovered, 1 eligible for generation, 1 already have a test")
                .contains("use --all");
        ArgumentCaptor<TestGenerationRequest> captor = ArgumentCaptor.forClass(TestGenerationRequest.class);
        verify(useCase).previewTargets(captor.capture());
        assertThat(captor.getValue().includedClasses()).containsExactly("com.acme.*");

        stdout.reset();
        new CommandLine(command()).execute("-p", tempDir.toString(), "--all");
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("skipped: interface");
    }

    @Test
    @DisplayName("scan errors are reported with exit code 2")
    void call_shouldReturnTwo_onScanError() {
        when(useCase.previewTargets(any())).thenThrow(new ScanException("boom"));

        int exitCode = new CommandLine(command()).execute("-p", tempDir.toString());

        assertThat(exitCode).isEqualTo(2);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("boom");
    }

    private ScanCommand command() {
        return new ScanCommand(useCase,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }
}
