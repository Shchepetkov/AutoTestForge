package com.autotestforge.core.service;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.ClassTarget;
import com.autotestforge.core.domain.ExternalContextSnippet;
import com.autotestforge.core.domain.ExternalTestContext;
import com.autotestforge.core.domain.FieldDependency;
import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.GenerationContext;
import com.autotestforge.core.domain.GenerationStatus;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.ScanPreview;
import com.autotestforge.core.domain.ScannedProject;
import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.AtfException;
import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.core.port.in.ScanProjectUseCase;
import com.autotestforge.core.port.out.AiTestGeneratorPort;
import com.autotestforge.core.port.out.BuildToolPort;
import com.autotestforge.core.port.out.ExternalContextPort;
import com.autotestforge.core.port.out.ProjectScannerPort;
import com.autotestforge.core.port.out.TestValidatorPort;
import com.autotestforge.core.port.out.TestWriterPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The hexagon center: orchestrates the whole pipeline
 * <p>
 * scan &rarr; select &rarr; generate (LLM) &rarr; write &rarr; validate &rarr; self-correct.
 * <p>
 * The service is deliberately framework-free; all infrastructure concerns live
 * behind ports. A failure for one class is recorded in the report and never
 * aborts the run. Classes can be processed concurrently (LLM calls are I/O
 * bound) while sandbox runs are serialized because they share the project's
 * build output. Interrupting the calling thread stops the run after the
 * classes in flight and returns the partial report.
 */
public class TestGenerationService implements GenerateTestsUseCase, ScanProjectUseCase {

    private static final Logger log = LoggerFactory.getLogger(TestGenerationService.class);

    /** Upper bound of related project types handed to the LLM per class. */
    static final int MAX_RELATED_TYPES = 8;

    private final ProjectScannerPort scanner;
    private final AiTestGeneratorPort testGenerator;
    private final TestWriterPort testWriter;
    private final BuildToolPort buildToolPort;
    private final TestValidatorPort validator;
    private final ExternalContextPort externalContextPort;
    private final ReentrantLock validationLock = new ReentrantLock(true);

    public TestGenerationService(ProjectScannerPort scanner,
                                 AiTestGeneratorPort testGenerator,
                                 TestWriterPort testWriter,
                                 BuildToolPort buildToolPort,
                                 TestValidatorPort validator,
                                 ExternalContextPort externalContextPort) {
        this.scanner = scanner;
        this.testGenerator = testGenerator;
        this.testWriter = testWriter;
        this.buildToolPort = buildToolPort;
        this.validator = validator;
        this.externalContextPort = externalContextPort == null ? ExternalContextPort.NO_OP : externalContextPort;
    }

    // ------------------------------------------------------------------ scan

    @Override
    public ScanPreview previewTargets(TestGenerationRequest request) {
        ScannedProject project = scanner.scan(request.projectPath());
        BuildTool buildTool = detectBuildToolQuietly(request.projectPath());
        List<ClassTarget> targets = project.classes().stream()
                .map(classInfo -> toTarget(classInfo, request))
                .toList();
        return new ScanPreview(request.projectPath(), buildTool, targets);
    }

    private ClassTarget toTarget(JavaClassInfo classInfo, TestGenerationRequest request) {
        Optional<String> skipReason = TargetSelector.skipReason(classInfo, request);
        Path existingTest = skipReason.isPresent()
                ? null
                : testWriter.locateExistingTest(classInfo, testClassNameFor(classInfo)).orElse(null);
        return new ClassTarget(
                classInfo.fullyQualifiedName(),
                classInfo.kind(),
                classInfo.springStereotype(),
                classInfo.publicMethods().stream().map(MethodInfo::name).distinct().toList(),
                classInfo.dependencies().stream().map(FieldDependency::type).distinct().toList(),
                classInfo.sourceFile(),
                skipReason.isEmpty(),
                skipReason.orElse(null),
                existingTest);
    }

    private BuildTool detectBuildToolQuietly(Path projectPath) {
        try {
            return buildToolPort.detect(projectPath);
        } catch (AtfException e) {
            log.warn("Build tool could not be detected for {}: {}", projectPath, e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------- generate

    @Override
    public TestGenerationReport generateTests(TestGenerationRequest request) {
        Instant startedAt = Instant.now();
        MDC.put("projectPath", request.projectPath().toString());
        try {
            log.info("Starting test generation for {}", request.projectPath());
            ScannedProject project = scanner.scan(request.projectPath());
            List<JavaClassInfo> targets = TargetSelector.select(project.classes(), request);
            request.progressListener().onScanCompleted(project.classes().size(), targets.size());
            log.info("Discovered {} classes, {} selected for test generation",
                    project.classes().size(), targets.size());

            BuildTool buildTool = buildToolPort.detect(request.projectPath());
            log.info("Detected build tool: {}", buildTool);
            if (request.writesIntoProject()) {
                buildToolPort.ensureTestDependencies(request.projectPath(), buildTool);
            }

            List<ClassGenerationResult> results = request.parallelism() > 1 && targets.size() > 1
                    ? processInParallel(targets, request, project, buildTool)
                    : processSequentially(targets, request, project, buildTool);

            TestGenerationReport report = new TestGenerationReport(
                    request.projectPath(), results, startedAt, Duration.between(startedAt, Instant.now()));
            log.info("Test generation finished: {}", report.summary());
            return report;
        } finally {
            externalContextPort.onRunFinished(request);
            MDC.remove("projectPath");
        }
    }

    private List<ClassGenerationResult> processSequentially(List<JavaClassInfo> targets,
                                                            TestGenerationRequest request,
                                                            ScannedProject project,
                                                            BuildTool buildTool) {
        List<ClassGenerationResult> results = new ArrayList<>();
        for (JavaClassInfo target : targets) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("Generation cancelled after {} of {} classes", results.size(), targets.size());
                break;
            }
            results.add(processTarget(target, request, project, buildTool));
        }
        return results;
    }

    private List<ClassGenerationResult> processInParallel(List<JavaClassInfo> targets,
                                                          TestGenerationRequest request,
                                                          ScannedProject project,
                                                          BuildTool buildTool) {
        int threads = Math.min(request.parallelism(), targets.size());
        log.info("Processing {} classes with {} worker threads", targets.size(), threads);
        String projectPath = request.projectPath().toString();
        ExecutorService pool = Executors.newFixedThreadPool(threads, workerThreadFactory());
        List<Future<ClassGenerationResult>> futures = new ArrayList<>();
        try {
            for (JavaClassInfo target : targets) {
                futures.add(pool.submit(() -> {
                    MDC.put("projectPath", projectPath);
                    try {
                        return processTarget(target, request, project, buildTool);
                    } finally {
                        MDC.clear();
                    }
                }));
            }
            List<ClassGenerationResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Generation cancelled; stopping {} pending class(es)", futures.size() - results.size());
                    pool.shutdownNow();
                    collectFinished(futures, results, i);
                    return results;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    log.error("Worker failed unexpectedly for {}", targets.get(i).fullyQualifiedName(), cause);
                    results.add(ClassGenerationResult.failed(targets.get(i).fullyQualifiedName(), 0,
                            "Unexpected error: " + cause.getMessage()));
                } catch (CancellationException e) {
                    log.warn("Generation of {} was cancelled", targets.get(i).fullyQualifiedName());
                }
            }
            return results;
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /** After cancellation, keep every result that completed before the interrupt. */
    private static void collectFinished(List<Future<ClassGenerationResult>> futures,
                                        List<ClassGenerationResult> results,
                                        int fromIndex) {
        for (int j = fromIndex; j < futures.size(); j++) {
            Future<ClassGenerationResult> future = futures.get(j);
            if (future.isDone() && !future.isCancelled()) {
                try {
                    results.add(future.get());
                } catch (InterruptedException | ExecutionException | CancellationException ignored) {
                    // a worker interrupted mid-flight has no result to report
                }
            }
        }
    }

    private static java.util.concurrent.ThreadFactory workerThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "atf-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    // ------------------------------------------------------------- per class

    private ClassGenerationResult processTarget(JavaClassInfo target,
                                                TestGenerationRequest request,
                                                ScannedProject project,
                                                BuildTool buildTool) {
        request.progressListener().onClassStarted(target.fullyQualifiedName());
        ClassGenerationResult result = generateForClass(target, request, project, buildTool);
        request.progressListener().onClassFinished(result);
        return result;
    }

    private ClassGenerationResult generateForClass(JavaClassInfo target,
                                                   TestGenerationRequest request,
                                                   ScannedProject project,
                                                   BuildTool buildTool) {
        MDC.put("className", target.fullyQualifiedName());
        int llmAttempts = 0;
        try {
            String testClassName = testClassNameFor(target);
            if (!request.overwriteExisting()) {
                Optional<Path> existing = testWriter.locateExistingTest(target, testClassName);
                if (existing.isPresent()) {
                    log.info("Skipping {}: test already exists at {} (use overwrite to regenerate)",
                            target.fullyQualifiedName(), existing.get());
                    return ClassGenerationResult.skipped(target.fullyQualifiedName(),
                            qualify(target.packageName(), testClassName), existing.get());
                }
            }

            log.info("Generating tests for {}", target.fullyQualifiedName());
            GenerationContext context = generationContext(target, request, project);
            GeneratedTestFile test = testGenerator.generate(target, request.llmProvider(), context);
            llmAttempts++;

            if (request.outputDir() != null) {
                Path written = testWriter.writeTestTo(request.outputDir(), request.projectPath(), target, test);
                log.info("Test written outside the project to {}", written);
                return new ClassGenerationResult(target.fullyQualifiedName(), test.fullyQualifiedName(),
                        GenerationStatus.GENERATED, written, llmAttempts, null, test.sourceCode());
            }
            if (request.dryRun()) {
                log.info("Dry-run: {} generated but not written", test.fullyQualifiedName());
                return new ClassGenerationResult(target.fullyQualifiedName(), test.fullyQualifiedName(),
                        GenerationStatus.GENERATED, null, llmAttempts, null, test.sourceCode());
            }

            Path writtenPath = testWriter.writeTest(target, test);
            log.info("Test written to {}", writtenPath);

            if (!request.validate()) {
                return new ClassGenerationResult(target.fullyQualifiedName(), test.fullyQualifiedName(),
                        GenerationStatus.WRITTEN, writtenPath, llmAttempts, null, test.sourceCode());
            }

            return validateWithSelfCorrection(target, test, writtenPath, request, buildTool, llmAttempts, context);
        } catch (AtfException e) {
            log.error("Failed to generate tests for {}: {}", target.fullyQualifiedName(), e.getMessage());
            return ClassGenerationResult.failed(target.fullyQualifiedName(), llmAttempts, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error while processing {}", target.fullyQualifiedName(), e);
            return ClassGenerationResult.failed(target.fullyQualifiedName(), llmAttempts,
                    "Unexpected error: " + e.getMessage());
        } finally {
            MDC.remove("className");
        }
    }

    private GenerationContext generationContext(JavaClassInfo target,
                                                TestGenerationRequest request,
                                                ScannedProject project) {
        List<ExternalContextSnippet> snippets = new ArrayList<>(request.externalContext().inlineSnippets());
        snippets.addAll(externalContextPort.fetchContext(target, request).snippets());
        ExternalTestContext externalContext = snippets.isEmpty()
                ? ExternalTestContext.empty()
                : new ExternalTestContext(snippets);
        if (!externalContext.isEmpty()) {
            log.info("Loaded {} external context snippet(s) for {}", snippets.size(), target.fullyQualifiedName());
        }
        List<JavaClassInfo> relatedTypes = RelatedTypesResolver.resolve(target, project, MAX_RELATED_TYPES);
        if (!relatedTypes.isEmpty()) {
            log.debug("Related project types for {}: {}", target.fullyQualifiedName(),
                    relatedTypes.stream().map(JavaClassInfo::fullyQualifiedName).toList());
        }
        return new GenerationContext(externalContext, relatedTypes);
    }

    /**
     * Runs the generated test in the sandbox; on failure, feeds the runner log
     * back to the LLM and retries up to {@code maxFixAttempts} times. Sandbox
     * runs are serialized across worker threads.
     */
    private ClassGenerationResult validateWithSelfCorrection(JavaClassInfo target,
                                                             GeneratedTestFile test,
                                                             Path writtenPath,
                                                             TestGenerationRequest request,
                                                             BuildTool buildTool,
                                                             int llmAttempts,
                                                             GenerationContext context) {
        ValidationResult result = runValidation(request, buildTool, test, writtenPath);
        int fixRounds = 0;

        while (!result.success() && fixRounds < request.maxFixAttempts()) {
            fixRounds++;
            log.info("Validation failed for {} ({} failures) - self-correction round {}/{}",
                    test.fullyQualifiedName(), result.failures().size(), fixRounds, request.maxFixAttempts());
            request.progressListener().onSelfCorrection(target.fullyQualifiedName(), fixRounds,
                    request.maxFixAttempts(), result.failures().size());
            test = testGenerator.fix(target, test, result, request.llmProvider(), context);
            llmAttempts++;
            writtenPath = testWriter.writeTest(target, test);
            result = runValidation(request, buildTool, test, writtenPath);
        }

        if (result.success()) {
            GenerationStatus status = fixRounds == 0
                    ? GenerationStatus.VALIDATED
                    : GenerationStatus.FIXED_AND_VALIDATED;
            log.info("{} validated successfully after {} LLM call(s)", test.fullyQualifiedName(), llmAttempts);
            return new ClassGenerationResult(target.fullyQualifiedName(), test.fullyQualifiedName(),
                    status, writtenPath, llmAttempts, null, test.sourceCode());
        }

        String error = "Still failing after " + fixRounds + " fix attempt(s): "
                + result.failures().stream().findFirst().map(f -> f.describe()).orElse(compilationHint(result));
        log.warn("Giving up on {}: {}", test.fullyQualifiedName(), error);
        return new ClassGenerationResult(target.fullyQualifiedName(), test.fullyQualifiedName(),
                GenerationStatus.VALIDATION_FAILED, writtenPath, llmAttempts, error, test.sourceCode());
    }

    private ValidationResult runValidation(TestGenerationRequest request, BuildTool buildTool,
                                           GeneratedTestFile test, Path writtenPath) {
        validationLock.lock();
        try {
            return validator.runTests(request.projectPath(), buildTool, test.fullyQualifiedName(), writtenPath);
        } finally {
            validationLock.unlock();
        }
    }

    /** Without structured failures the build itself failed; surface the first error line of the log. */
    private static String compilationHint(ValidationResult result) {
        String rawLog = result.rawLog() == null ? "" : result.rawLog();
        return rawLog.lines()
                .filter(line -> line.contains("ERROR") || line.contains("error:"))
                .findFirst()
                .map(String::strip)
                .orElse("build failed, see log");
    }

    static String testClassNameFor(JavaClassInfo classInfo) {
        return classInfo.className() + "Test";
    }

    private static String qualify(String packageName, String className) {
        return packageName == null || packageName.isBlank() ? className : packageName + "." + className;
    }
}
