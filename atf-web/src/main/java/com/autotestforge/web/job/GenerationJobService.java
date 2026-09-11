package com.autotestforge.web.job;

import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.ExternalContextRequest;
import com.autotestforge.core.domain.ExternalContextSnippet;
import com.autotestforge.core.domain.ExternalContextSourceRequest;
import com.autotestforge.core.domain.ProgressListener;
import com.autotestforge.core.domain.ScanPreview;
import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.core.port.in.ScanProjectUseCase;
import com.autotestforge.spring.AtfProperties;
import com.autotestforge.web.api.ScanRequest;
import com.autotestforge.web.api.StartGenerationRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * In-memory job registry: starts generation runs asynchronously, exposes their
 * live progress to the REST layer, supports cooperative cancellation and keeps
 * a bounded history of finished jobs.
 */
@Service
public class GenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobService.class);

    /** Finished jobs kept in memory; the oldest are evicted first. */
    static final int MAX_RETAINED_JOBS = 50;

    private final GenerateTestsUseCase generateTestsUseCase;
    private final ScanProjectUseCase scanProjectUseCase;
    private final AtfProperties properties;
    private final Map<String, GenerationJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "atf-web-job");
        thread.setDaemon(true);
        return thread;
    });

    public GenerationJobService(GenerateTestsUseCase generateTestsUseCase,
                                ScanProjectUseCase scanProjectUseCase,
                                AtfProperties properties) {
        this.generateTestsUseCase = generateTestsUseCase;
        this.scanProjectUseCase = scanProjectUseCase;
        this.properties = properties;
    }

    /** Synchronous scan preview (no LLM calls, nothing written). */
    public ScanPreview scan(ScanRequest request) {
        return scanProjectUseCase.previewTargets(TestGenerationRequest.builder(Path.of(request.projectPath()))
                .includedClasses(request.classes())
                .excludedClasses(request.excludes())
                .build());
    }

    public GenerationJob start(StartGenerationRequest request) {
        String id = UUID.randomUUID().toString();
        GenerationJob job = new GenerationJob(id, request.projectPath());
        jobs.put(id, job);
        evictOldJobs();
        Future<?> handle = executor.submit(() -> run(job, request));
        job.attach(handle);
        log.info("Started generation job {} for {}", id, request.projectPath());
        return job;
    }

    public Optional<GenerationJob> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<GenerationJob> all() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(GenerationJob::getCreatedAt).reversed())
                .toList();
    }

    /** @return true when the job existed and was still running */
    public boolean cancel(String id) {
        GenerationJob job = jobs.get(id);
        if (job == null) {
            return false;
        }
        boolean cancelled = job.cancel();
        if (cancelled) {
            log.info("Cancellation requested for job {}", id);
        }
        return cancelled;
    }

    private void run(GenerationJob job, StartGenerationRequest request) {
        try {
            TestGenerationReport report = generateTestsUseCase.generateTests(toRequest(job, request));
            job.addEvent("Finished: " + report.summary());
            job.complete(report);
        } catch (Exception e) {
            log.error("Generation job {} failed", job.getId(), e);
            job.addEvent("Failed: " + e.getMessage());
            job.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            Thread.interrupted();   // clear a cancellation interrupt so the pool thread stays usable
        }
    }

    private TestGenerationRequest toRequest(GenerationJob job, StartGenerationRequest request) {
        return TestGenerationRequest.builder(Path.of(request.projectPath()))
                .includedClasses(request.classes())
                .excludedClasses(request.excludes())
                .llmProvider(request.llm())
                .validate(request.validate())
                .dryRun(request.dryRun())
                .overwriteExisting(request.overwrite() != null
                        ? request.overwrite()
                        : properties.generation().overwriteExisting())
                .maxFixAttempts(request.maxFixAttempts() != null
                        ? request.maxFixAttempts()
                        : properties.validation().maxFixAttempts())
                .parallelism(request.parallelism() != null
                        ? request.parallelism()
                        : properties.generation().parallelism())
                .outputDir(request.outputDir() == null || request.outputDir().isBlank()
                        ? null
                        : Path.of(request.outputDir().strip()))
                .externalContext(externalContextRequest(request))
                .progressListener(new JobProgressListener(job))
                .build();
    }

    private ExternalContextRequest externalContextRequest(StartGenerationRequest request) {
        List<String> sources = request.contextSources() == null ? List.of() : request.contextSources();
        List<ExternalContextSourceRequest> mcpSources = mcpSources(request);
        List<ExternalContextSnippet> inlineSnippets = contextFiles(request);
        boolean enabled = request.context() || request.contextQuery() != null || !sources.isEmpty()
                || !mcpSources.isEmpty() || !inlineSnippets.isEmpty();
        return new ExternalContextRequest(enabled, request.contextQuery(), sources, mcpSources, inlineSnippets);
    }

    private List<ExternalContextSourceRequest> mcpSources(StartGenerationRequest request) {
        if (request.mcpSources() == null) {
            return List.of();
        }
        return request.mcpSources().stream()
                .filter(source -> source != null && source.enabled())
                .map(source -> new ExternalContextSourceRequest(
                        source.name(),
                        source.enabled(),
                        source.command(),
                        source.args(),
                        source.toolName(),
                        source.queryArgument(),
                        source.queryTemplate(),
                        Map.of(),
                        source.timeoutSeconds() == null || source.timeoutSeconds() <= 0
                                ? null
                                : Duration.ofSeconds(source.timeoutSeconds()),
                        source.maxChars()))
                .toList();
    }

    private List<ExternalContextSnippet> contextFiles(StartGenerationRequest request) {
        if (request.contextFiles() == null) {
            return List.of();
        }
        return request.contextFiles().stream()
                .filter(file -> file != null && file.content() != null && !file.content().isBlank())
                .map(file -> new ExternalContextSnippet("uploaded-file",
                        file.name() == null || file.name().isBlank() ? "Uploaded TMS context" : file.name(),
                        file.content()))
                .toList();
    }

    private void evictOldJobs() {
        if (jobs.size() <= MAX_RETAINED_JOBS) {
            return;
        }
        jobs.values().stream()
                .filter(job -> job.getState().isTerminal())
                .sorted(Comparator.comparing(GenerationJob::getCreatedAt))
                .limit(jobs.size() - MAX_RETAINED_JOBS)
                .forEach(job -> jobs.remove(job.getId()));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private record JobProgressListener(GenerationJob job) implements ProgressListener {

        @Override
        public void onScanCompleted(int discoveredClasses, int selectedClasses) {
            job.setTotalClasses(selectedClasses);
            job.addEvent("Scanned " + discoveredClasses + " classes, " + selectedClasses + " selected");
        }

        @Override
        public void onClassStarted(String classFqn) {
            job.addEvent("Generating tests for " + classFqn);
        }

        @Override
        public void onSelfCorrection(String classFqn, int round, int maxRounds, int failureCount) {
            job.addEvent(classFqn + ": validation failed (" + failureCount + " failure(s)), self-correction "
                    + round + "/" + maxRounds);
        }

        @Override
        public void onClassFinished(ClassGenerationResult result) {
            job.addResult(result);
            job.addEvent(result.classFqn() + " -> " + result.status()
                    + (result.errorMessage() == null ? "" : ": " + firstLine(result.errorMessage())));
        }

        private static String firstLine(String value) {
            int newline = value.indexOf('\n');
            return newline < 0 ? value : value.substring(0, newline);
        }
    }
}
