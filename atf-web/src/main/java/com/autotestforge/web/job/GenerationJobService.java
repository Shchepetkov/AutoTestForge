package com.autotestforge.web.job;

import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.ExternalContextRequest;
import com.autotestforge.core.domain.ExternalContextSnippet;
import com.autotestforge.core.domain.ExternalContextSourceRequest;
import com.autotestforge.core.domain.ProgressListener;
import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.web.api.StartGenerationRequest;
import com.autotestforge.web.config.AtfProperties;
import com.autotestforge.web.llm.GenerationUseCaseFactory;
import com.autotestforge.web.security.ProjectAccessPolicy;
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

    private final GenerationUseCaseFactory useCaseFactory;
    private final AtfProperties properties;
    private final ProjectAccessPolicy accessPolicy;
    private final Map<String, GenerationJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public GenerationJobService(GenerationUseCaseFactory useCaseFactory, AtfProperties properties,
                                ProjectAccessPolicy accessPolicy) {
        this.useCaseFactory = useCaseFactory;
        this.properties = properties;
        this.accessPolicy = accessPolicy;
    }

    public GenerationJob start(StartGenerationRequest request) {
        Path projectPath = accessPolicy.requireAllowedProject(request.projectPath());
        // Validate connection settings before creating a job or adding work to the queue.
        GenerateTestsUseCase useCase = useCaseFactory.create(request.llmConnection(), request.llm());
        String id = UUID.randomUUID().toString();
        GenerationJob job = new GenerationJob(id, projectPath.toString());
        jobs.put(id, job);
        executor.submit(() -> run(job, request, projectPath, useCase));
        log.info("Started generation job {} for {}", id, projectPath);
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

    private void run(GenerationJob job, StartGenerationRequest request, Path projectPath, GenerateTestsUseCase useCase) {
        try {
            TestGenerationRequest generationRequest = TestGenerationRequest
                    .builder(projectPath)
                    .includedClasses(request.classes() == null ? List.of() : request.classes())
                    .llmProvider(request.llm())
                    .validate(request.validate())
                    .dryRun(request.dryRun())
                    .maxFixAttempts(properties.validation().maxFixAttempts())
                    .externalContext(externalContextRequest(request))
                    .progressListener(new JobProgressListener(job))
                    .build();
            TestGenerationReport report = useCase.generateTests(generationRequest);
            job.complete(report);
        } catch (Exception e) {
            log.error("Generation job {} failed", job.getId(), e);
            job.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
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
                        null,
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
