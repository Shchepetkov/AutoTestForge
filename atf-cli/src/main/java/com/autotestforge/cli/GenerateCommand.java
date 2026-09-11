package com.autotestforge.cli;

import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.ExternalContextRequest;
import com.autotestforge.core.domain.ProgressListener;
import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.exception.AtfException;
import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.core.report.ReportFormatter;
import com.autotestforge.spring.AtfProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@code atf generate} command: scans the target project, generates tests
 * with the configured LLM and (optionally) validates them in isolation.
 * <p>
 * Exit codes: 0 - every processed class succeeded or was skipped, 1 - at least
 * one class failed, 2 - the run itself could not be executed.
 */
@Component
@Command(name = "generate",
        mixinStandardHelpOptions = true,
        description = "Scan a Java project and generate JUnit 5 tests with an LLM.")
public class GenerateCommand implements Callable<Integer> {

    private final GenerateTestsUseCase generateTestsUseCase;
    private final AtfProperties properties;
    private final PrintStream out;
    private final PrintStream err;

    @Option(names = {"--project-path", "-p"}, required = true,
            description = "Root directory of the target Java project (Maven or Gradle).")
    Path projectPath;

    @Option(names = {"--classes", "-c"}, split = ",",
            description = "Restrict generation to these classes: simple or fully qualified names, "
                    + "wildcards allowed (e.g. com.acme.service.*, *Service).")
    List<String> classes = List.of();

    @Option(names = {"--exclude", "-x"}, split = ",",
            description = "Leave these classes out (same syntax as --classes).")
    List<String> excludes = List.of();

    @Option(names = "--llm",
            description = "LLM provider for this run: ollama, openai, anthropic or offline "
                    + "(default: configured provider).")
    String llmProvider;

    @Option(names = "--validate",
            description = "Run the generated tests in an isolated environment and self-correct failures.")
    boolean validate;

    @Option(names = "--dry-run",
            description = "Generate tests but do not modify the target project.")
    boolean dryRun;

    @Option(names = "--overwrite",
            description = "Regenerate classes that already have a test (default: they are skipped).")
    Boolean overwrite;

    @Option(names = "--max-fix-attempts",
            description = "Self-correction rounds per class (default: from configuration).")
    Integer maxFixAttempts;

    @Option(names = {"--parallelism", "-j"},
            description = "Number of classes processed concurrently, 1-16 (default: from configuration).")
    Integer parallelism;

    @Option(names = {"--output-dir", "-o"},
            description = "Write generated tests under this directory (mirroring the module layout) "
                    + "instead of the target project.")
    Path outputDir;

    @Option(names = "--print",
            description = "Print every generated test class to stdout (handy with --dry-run).")
    boolean printSources;

    @Option(names = "--report-json",
            description = "Write a machine-readable JSON report to this file.")
    Path reportJson;

    @Option(names = "--report-markdown",
            description = "Write a Markdown report (e.g. for a PR comment or job summary) to this file.")
    Path reportMarkdown;

    @Option(names = "--with-external-context",
            description = "Fetch business/TMS context from configured MCP sources before generation.")
    boolean withExternalContext;

    @Option(names = "--context-sources", split = ",",
            description = "Comma-separated MCP context source names to use (for example: confluence,zephyr).")
    List<String> contextSources = List.of();

    @Option(names = "--context-query",
            description = "Override MCP search query template. Supports ${className}, ${fullyQualifiedName}, "
                    + "${packageName}, ${projectPath}, ${methods}.")
    String contextQuery;

    @Autowired
    public GenerateCommand(GenerateTestsUseCase generateTestsUseCase, AtfProperties properties) {
        this(generateTestsUseCase, properties, System.out, System.err);
    }

    GenerateCommand(GenerateTestsUseCase generateTestsUseCase, AtfProperties properties,
                    PrintStream out, PrintStream err) {
        this.generateTestsUseCase = generateTestsUseCase;
        this.properties = properties;
        this.out = out;
        this.err = err;
    }

    @Override
    public Integer call() {
        try {
            TestGenerationRequest request = buildRequest();
            TestGenerationReport report = generateTestsUseCase.generateTests(request);
            printReport(report);
            if (printSources) {
                printSources(report);
            }
            writeReports(report);
            return report.failed() == 0 ? 0 : 1;
        } catch (AtfException | IllegalArgumentException e) {
            err.println("ERROR: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("ERROR: could not write report: " + e.getMessage());
            return 2;
        }
    }

    TestGenerationRequest buildRequest() {
        return TestGenerationRequest.builder(projectPath.toAbsolutePath().normalize())
                .includedClasses(classes)
                .excludedClasses(excludes)
                .llmProvider(llmProvider)
                .validate(validate)
                .dryRun(dryRun)
                .overwriteExisting(overwrite != null ? overwrite : properties.generation().overwriteExisting())
                .maxFixAttempts(maxFixAttempts != null ? maxFixAttempts : properties.validation().maxFixAttempts())
                .parallelism(parallelism != null ? parallelism : properties.generation().parallelism())
                .outputDir(outputDir == null ? null : outputDir.toAbsolutePath().normalize())
                .externalContext(externalContextRequest())
                .progressListener(new ConsoleProgressListener(out))
                .build();
    }

    private ExternalContextRequest externalContextRequest() {
        boolean enabled = withExternalContext || contextQuery != null || !contextSources.isEmpty();
        return new ExternalContextRequest(enabled, contextQuery, contextSources);
    }

    private void writeReports(TestGenerationReport report) throws IOException {
        if (reportJson != null) {
            write(reportJson, ReportFormatter.toJson(report, dryRun || outputDir != null));
            out.println("JSON report written to " + reportJson.toAbsolutePath());
        }
        if (reportMarkdown != null) {
            write(reportMarkdown, ReportFormatter.toMarkdown(report));
            out.println("Markdown report written to " + reportMarkdown.toAbsolutePath());
        }
    }

    private static void write(Path file, String content) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content);
    }

    private void printReport(TestGenerationReport report) {
        out.println();
        out.println("=".repeat(110));
        out.printf("  AutoTestForge report for %s%n", report.projectPath());
        out.println("=".repeat(110));
        out.printf("  %-45s %-22s %-5s %s%n", "CLASS", "STATUS", "LLM", "DETAILS");
        out.println("-".repeat(110));
        for (ClassGenerationResult result : report.results()) {
            out.printf("  %-45s %-22s %-5d %s%n",
                    shorten(result.classFqn(), 45), result.status(), result.llmAttempts(), details(result));
        }
        out.println("-".repeat(110));
        out.println("  " + report.summary());
        out.println("=".repeat(110));
    }

    private void printSources(TestGenerationReport report) {
        for (ClassGenerationResult result : report.results()) {
            if (result.testSource() == null) {
                continue;
            }
            out.println();
            out.println("// ---- " + result.testClassFqn() + " (" + result.status() + ") ----");
            out.println(result.testSource());
        }
    }

    private String details(ClassGenerationResult result) {
        if (result.errorMessage() != null) {
            return firstLine(result.errorMessage());
        }
        if (result.isSkipped()) {
            return "existing test kept: " + result.writtenPath() + " (use --overwrite)";
        }
        return result.writtenPath() != null ? result.writtenPath().toString() : "-";
    }

    private String shorten(String value, int max) {
        return value.length() <= max ? value : "..." + value.substring(value.length() - max + 3);
    }

    private String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    /** Live per-class progress on stdout; thread-safe for parallel runs. */
    static final class ConsoleProgressListener implements ProgressListener {

        private final PrintStream out;
        private final AtomicInteger processed = new AtomicInteger();
        private volatile int total;

        ConsoleProgressListener(PrintStream out) {
            this.out = out;
        }

        @Override
        public void onScanCompleted(int discoveredClasses, int selectedClasses) {
            this.total = selectedClasses;
            out.printf("Scanned %d classes, %d selected for generation%n", discoveredClasses, selectedClasses);
        }

        @Override
        public void onClassStarted(String classFqn) {
            out.printf("[%d/%d] %s ...%n", Math.min(processed.get() + 1, total), total, classFqn);
        }

        @Override
        public void onSelfCorrection(String classFqn, int round, int maxRounds, int failureCount) {
            out.printf("      %s: validation failed (%d failure(s)), self-correction %d/%d%n",
                    classFqn, failureCount, round, maxRounds);
        }

        @Override
        public void onClassFinished(ClassGenerationResult result) {
            int done = processed.incrementAndGet();
            out.printf("[%d/%d] %s -> %s%n", done, total, result.classFqn(), result.status());
        }
    }
}
