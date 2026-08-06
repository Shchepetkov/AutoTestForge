package com.autotestforge.web.job;

import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.ProgressListener;
import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.web.api.StartGenerationRequest;
import com.autotestforge.web.config.AtfProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-memory job registry: starts generation runs asynchronously and exposes
 * their live progress to the REST layer.
 */
@Service
public class GenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobService.class);

    private final GenerateTestsUseCase generateTestsUseCase;
    private final AtfProperties properties;
    private final Map<String, GenerationJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public GenerationJobService(GenerateTestsUseCase generateTestsUseCase, AtfProperties properties) {
        this.generateTestsUseCase = generateTestsUseCase;
        this.properties = properties;
    }

    public GenerationJob start(StartGenerationRequest request) {
        String id = UUID.randomUUID().toString();
        GenerationJob job = new GenerationJob(id, request.projectPath());
        jobs.put(id, job);
        executor.submit(() -> run(job, request));
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

    private void run(GenerationJob job, StartGenerationRequest request) {
        try {
            TestGenerationRequest generationRequest = TestGenerationRequest
                    .builder(Path.of(request.projectPath()))
                    .includedClasses(request.classes() == null ? List.of() : request.classes())
                    .llmProvider(request.llm())
                    .validate(request.validate())
                    .dryRun(request.dryRun())
                    .maxFixAttempts(properties.validation().maxFixAttempts())
                    .progressListener(new JobProgressListener(job))
                    .build();
            TestGenerationReport report = generateTestsUseCase.generateTests(generationRequest);
            job.complete(report);
        } catch (Exception e) {
            log.error("Generation job {} failed", job.getId(), e);
            job.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
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
        public void onClassFinished(ClassGenerationResult result) {
            job.addResult(result);
            job.addEvent(result.classFqn() + " -> " + result.status());
        }
    }
}
