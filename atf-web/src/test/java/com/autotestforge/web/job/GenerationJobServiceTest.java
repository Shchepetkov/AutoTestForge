package com.autotestforge.web.job;

import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.GenerationStatus;
import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.exception.ScanException;
import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.core.port.in.ScanProjectUseCase;
import com.autotestforge.spring.AtfProperties;
import com.autotestforge.web.api.ScanRequest;
import com.autotestforge.web.api.StartGenerationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationJobServiceTest {

    private final GenerateTestsUseCase generateUseCase = mock(GenerateTestsUseCase.class);
    private final ScanProjectUseCase scanUseCase = mock(ScanProjectUseCase.class);
    private final AtfProperties properties = new AtfProperties(null,
            new AtfProperties.Generation(2, true),
            new AtfProperties.Validation(3, false, "m", "g", Duration.ofMinutes(1), Path.of("/tmp")),
            null);
    private final GenerationJobService service = new GenerationJobService(generateUseCase, scanUseCase, properties);

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    @DisplayName("a started job runs asynchronously, records progress and completes with the report")
    void start_shouldRunJobAndComplete() {
        ClassGenerationResult result = new ClassGenerationResult("com.acme.A", "com.acme.ATest",
                GenerationStatus.WRITTEN, Path.of("/repo/ATest.java"), 1, null, "class ATest {}");
        when(generateUseCase.generateTests(any())).thenAnswer(invocation -> {
            TestGenerationRequest request = invocation.getArgument(0);
            request.progressListener().onScanCompleted(5, 1);
            request.progressListener().onClassStarted("com.acme.A");
            request.progressListener().onSelfCorrection("com.acme.A", 1, 2, 3);
            request.progressListener().onClassFinished(result);
            return new TestGenerationReport(Path.of("/repo"), List.of(result), Instant.now(), Duration.ofSeconds(1));
        });

        GenerationJob job = service.start(request(null, null, null, null));

        await().atMost(5, TimeUnit.SECONDS).until(() -> job.getState().isTerminal());
        assertThat(job.getState()).isEqualTo(GenerationJob.State.COMPLETED);
        assertThat(job.getTotalClasses()).isEqualTo(1);
        assertThat(job.getProcessed()).isEqualTo(1);
        assertThat(job.getResults()).containsExactly(result);
        assertThat(job.getEvents()).anySatisfy(event -> assertThat(event).contains("Scanned 5 classes, 1 selected"));
        assertThat(job.getEvents()).anySatisfy(event -> assertThat(event).contains("self-correction 1/2"));
        assertThat(job.getEvents()).anySatisfy(event -> assertThat(event).contains("Finished: processed=1"));
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(service.find(job.getId())).contains(job);
        assertThat(service.all()).containsExactly(job);
    }

    @Test
    @DisplayName("request fields override configured defaults; absent fields fall back to configuration")
    void start_shouldMapRequestOntoDomainRequest() {
        when(generateUseCase.generateTests(any())).thenReturn(
                new TestGenerationReport(Path.of("/repo"), List.of(), Instant.now(), Duration.ZERO));

        GenerationJob withOverrides = service.start(request(false, 0, 5, "/out"));
        await().atMost(5, TimeUnit.SECONDS).until(() -> withOverrides.getState().isTerminal());
        GenerationJob withDefaults = service.start(request(null, null, null, null));
        await().atMost(5, TimeUnit.SECONDS).until(() -> withDefaults.getState().isTerminal());

        ArgumentCaptor<TestGenerationRequest> captor = ArgumentCaptor.forClass(TestGenerationRequest.class);
        verify(generateUseCase, org.mockito.Mockito.times(2)).generateTests(captor.capture());
        TestGenerationRequest overridden = captor.getAllValues().get(0);
        assertThat(overridden.overwriteExisting()).isFalse();
        assertThat(overridden.maxFixAttempts()).isZero();
        assertThat(overridden.parallelism()).isEqualTo(5);
        assertThat(overridden.outputDir()).isEqualTo(Path.of("/out"));
        assertThat(overridden.includedClasses()).containsExactly("com.acme.A");
        assertThat(overridden.excludedClasses()).containsExactly("*Config");
        assertThat(overridden.externalContext().enabled()).isTrue();
        assertThat(overridden.externalContext().mcpSources()).hasSize(1);
        assertThat(overridden.externalContext().mcpSources().get(0).timeout()).isEqualTo(Duration.ofSeconds(12));
        assertThat(overridden.externalContext().inlineSnippets()).hasSize(1);

        TestGenerationRequest defaults = captor.getAllValues().get(1);
        assertThat(defaults.overwriteExisting()).isTrue();
        assertThat(defaults.maxFixAttempts()).isEqualTo(3);
        assertThat(defaults.parallelism()).isEqualTo(2);
        assertThat(defaults.outputDir()).isNull();
    }

    @Test
    @DisplayName("a failing pipeline marks the job FAILED with the error message")
    void start_shouldMarkJobFailed_whenPipelineThrows() {
        when(generateUseCase.generateTests(any())).thenThrow(new ScanException("No src/main/java"));

        GenerationJob job = service.start(request(null, null, null, null));

        await().atMost(5, TimeUnit.SECONDS).until(() -> job.getState().isTerminal());
        assertThat(job.getState()).isEqualTo(GenerationJob.State.FAILED);
        assertThat(job.getError()).contains("No src/main/java");
    }

    @Test
    @DisplayName("cancelling a running job interrupts the worker and ends in CANCELLED")
    void cancel_shouldInterruptRunningJob() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        when(generateUseCase.generateTests(any())).thenAnswer(invocation -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new TestGenerationReport(Path.of("/repo"), List.of(), Instant.now(), Duration.ZERO);
        });
        GenerationJob job = service.start(request(null, null, null, null));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(service.cancel(job.getId())).isTrue();

        await().atMost(5, TimeUnit.SECONDS).until(() -> job.getState().isTerminal());
        assertThat(job.getState()).isEqualTo(GenerationJob.State.CANCELLED);
        assertThat(job.isCancelRequested()).isTrue();
        assertThat(service.cancel(job.getId())).isFalse();
        assertThat(service.cancel("unknown")).isFalse();
    }

    @Test
    @DisplayName("scan previews delegate to the scan use case with the request filters")
    void scan_shouldDelegate() {
        service.scan(new ScanRequest("/repo", List.of("A"), List.of("B")));

        ArgumentCaptor<TestGenerationRequest> captor = ArgumentCaptor.forClass(TestGenerationRequest.class);
        verify(scanUseCase).previewTargets(captor.capture());
        assertThat(captor.getValue().projectPath()).isEqualTo(Path.of("/repo"));
        assertThat(captor.getValue().includedClasses()).containsExactly("A");
        assertThat(captor.getValue().excludedClasses()).containsExactly("B");
    }

    private static StartGenerationRequest request(Boolean overwrite, Integer maxFix, Integer parallelism,
                                                  String outputDir) {
        return new StartGenerationRequest("/repo", List.of("com.acme.A"), List.of("*Config"), "offline",
                false, true, overwrite, maxFix, parallelism, outputDir, false, null, null,
                List.of(new StartGenerationRequest.McpSourceRequest("jira", true, "npx", List.of("-y", "x"),
                        "search", "query", null, 12, 4000)),
                List.of(new StartGenerationRequest.ContextFileRequest("zephyr.xml", "text/xml", "<tests/>")));
    }
}
