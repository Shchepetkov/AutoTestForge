package com.autotestforge.core.service;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.ExternalTestContext;
import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.GenerationStatus;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.ScannedProject;
import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.AtfException;
import com.autotestforge.core.port.in.GenerateTestsUseCase;
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

/**
 * The hexagon center: orchestrates the whole pipeline
 * <p>
 * scan &rarr; generate (LLM) &rarr; write &rarr; validate &rarr; self-correct.
 * <p>
 * The service is deliberately framework-free; all infrastructure concerns live
 * behind ports. A failure for one class is recorded in the report and never
 * aborts the run.
 */
public class TestGenerationService implements GenerateTestsUseCase {

    private static final Logger log = LoggerFactory.getLogger(TestGenerationService.class);

    private final ProjectScannerPort scanner;
    private final AiTestGeneratorPort testGenerator;
    private final TestWriterPort testWriter;
    private final BuildToolPort buildToolPort;
    private final TestValidatorPort validator;
    private final ExternalContextPort externalContextPort;

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

    @Override
    public TestGenerationReport generateTests(TestGenerationRequest request) {
        Instant startedAt = Instant.now();
        MDC.put("projectPath", request.projectPath().toString());
        try {
            log.info("Starting test generation for {}", request.projectPath());
            ScannedProject project = scanner.scan(request.projectPath());
            List<JavaClassInfo> targets = selectTargets(project.classes(), request);
            request.progressListener().onScanCompleted(project.classes().size(), targets.size());
            log.info("Discovered {} classes, {} selected for test generation",
                    project.classes().size(), targets.size());

            BuildTool buildTool = buildToolPort.detect(request.projectPath());
            log.info("Detected build tool: {}", buildTool);
            if (!request.dryRun()) {
                buildToolPort.ensureTestDependencies(request.projectPath(), buildTool);
            }

            List<ClassGenerationResult> results = new ArrayList<>();
            for (JavaClassInfo target : targets) {
                request.progressListener().onClassStarted(target.fullyQualifiedName());
                ClassGenerationResult result = generateForClass(target, request, buildTool);
                results.add(result);
                request.progressListener().onClassFinished(result);
            }

            TestGenerationReport report = new TestGenerationReport(
                    request.projectPath(), List.copyOf(results), startedAt,
                    Duration.between(startedAt, Instant.now()));
            log.info("Test generation finished: {}", report.summary());
            return report;
        } finally {
            MDC.remove("projectPath");
        }
    }

    /**
     * A class is worth testing when it is a concrete type with public behavior.
     * Interfaces, abstract classes and application entry points are skipped.
     */
    private List<JavaClassInfo> selectTargets(List<JavaClassInfo> classes, TestGenerationRequest request) {
        return classes.stream()
                .filter(c -> c.kind() != ClassKind.INTERFACE && !c.isAbstract())
                .filter(c -> !c.publicMethods().isEmpty())
                .filter(c -> !c.annotations().contains("SpringBootApplication"))
                .filter(c -> matchesFilter(c, request.includedClasses()))
                .toList();
    }

    private boolean matchesFilter(JavaClassInfo classInfo, List<String> includedClasses) {
        if (includedClasses.isEmpty()) {
            return true;
        }
        return includedClasses.stream().anyMatch(name ->
                name.equals(classInfo.className()) || name.equals(classInfo.fullyQualifiedName()));
    }

    private ClassGenerationResult generateForClass(JavaClassInfo target,
                                                   TestGenerationRequest request,
                                                   BuildTool buildTool) {
        MDC.put("className", target.fullyQualifiedName());
        int llmAttempts = 0;
        try {
            log.info("Generating tests for {}", target.fullyQualifiedName());
            ExternalTestContext externalContext = externalContext(target, request);
            if (!externalContext.isEmpty()) {
                log.info("Loaded {} external context snippet(s) for {}",
                        externalContext.snippets().size(), target.fullyQualifiedName());
            }
            GeneratedTestFile test = testGenerator.generate(target, request.llmProvider(), externalContext);
            llmAttempts++;

            if (request.dryRun()) {
                log.info("Dry-run: {} generated but not written", test.fullyQualifiedName());
                return new ClassGenerationResult(target.fullyQualifiedName(), test.fullyQualifiedName(),
                        GenerationStatus.GENERATED, null, llmAttempts, null);
            }

            Path writtenPath = testWriter.writeTest(target, test);
            log.info("Test written to {}", writtenPath);

            if (!request.validate()) {
                return new ClassGenerationResult(target.fullyQualifiedName(), test.fullyQualifiedName(),
                        GenerationStatus.WRITTEN, writtenPath, llmAttempts, null);
            }

            return validateWithSelfCorrection(target, test, writtenPath, request, buildTool, llmAttempts, externalContext);
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

    private ExternalTestContext externalContext(JavaClassInfo target, TestGenerationRequest request) {
        List<com.autotestforge.core.domain.ExternalContextSnippet> snippets = new ArrayList<>(
                request.externalContext().inlineSnippets());
        snippets.addAll(externalContextPort.fetchContext(target, request).snippets());
        return snippets.isEmpty() ? ExternalTestContext.empty() : new ExternalTestContext(snippets);
    }

    /**
     * Runs the generated test in the sandbox; on failure, feeds the runner log
     * back to the LLM and retries up to {@code maxFixAttempts} times.
     */
    private ClassGenerationResult validateWithSelfCorrection(JavaClassInfo target,
                                                             GeneratedTestFile test,
                                                             Path writtenPath,
                                                             TestGenerationRequest request,
                                                             BuildTool buildTool,
                                                             int llmAttempts,
                                                             ExternalTestContext externalContext) {
        ValidationResult result = validator.runTests(request.projectPath(), buildTool, test.fullyQualifiedName());
        int fixRounds = 0;

        while (!result.success() && fixRounds < request.maxFixAttempts()) {
            fixRounds++;
            log.info("Validation failed for {} ({} failures) - self-correction round {}/{}",
                    test.fullyQualifiedName(), result.failures().size(), fixRounds, request.maxFixAttempts());
            test = testGenerator.fix(target, test, result, request.llmProvider(), externalContext);
            llmAttempts++;
            writtenPath = testWriter.writeTest(target, test);
            result = validator.runTests(request.projectPath(), buildTool, test.fullyQualifiedName());
        }

        if (result.success()) {
            GenerationStatus status = fixRounds == 0
                    ? GenerationStatus.VALIDATED
                    : GenerationStatus.FIXED_AND_VALIDATED;
            log.info("{} validated successfully after {} LLM call(s)", test.fullyQualifiedName(), llmAttempts);
            return new ClassGenerationResult(target.fullyQualifiedName(), test.fullyQualifiedName(),
                    status, writtenPath, llmAttempts, null);
        }

        String error = "Still failing after " + fixRounds + " fix attempt(s): "
                + result.failures().stream().findFirst().map(f -> f.describe()).orElse("see log");
        log.warn("Giving up on {}: {}", test.fullyQualifiedName(), error);
        return new ClassGenerationResult(target.fullyQualifiedName(), test.fullyQualifiedName(),
                GenerationStatus.VALIDATION_FAILED, writtenPath, llmAttempts, error);
    }
}
