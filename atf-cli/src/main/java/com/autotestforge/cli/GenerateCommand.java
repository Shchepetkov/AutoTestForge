package com.autotestforge.cli;

import com.autotestforge.cli.config.AtfProperties;
import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.ExternalContextRequest;
import com.autotestforge.core.domain.ProgressListener;
import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.exception.AtfException;
import com.autotestforge.core.port.in.GenerateTestsUseCase;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@code atf generate} command: scans the target project, generates tests
 * with the configured LLM and (optionally) validates them in isolation.
 */
@Component
@Command(name = "generate",
        mixinStandardHelpOptions = true,
        description = "Scan a Java project and generate JUnit 5 tests with an LLM.")
public class GenerateCommand implements Callable<Integer> {

    private final GenerateTestsUseCase generateTestsUseCase;
    private final AtfProperties properties;

    @Option(names = {"--project-path", "-p"}, required = true,
            description = "Root directory of the target Java project (Maven or Gradle).")
    private Path projectPath;

    @Option(names = {"--classes", "-c"}, split = ",",
            description = "Restrict generation to these classes (simple or fully qualified names).")
    private List<String> classes = List.of();

    @Option(names = "--llm",
            description = "LLM provider for this run: ollama, openai or offline (default: configured provider).")
    private String llmProvider;

    @Option(names = "--validate",
            description = "Run the generated tests in an isolated environment and self-correct failures.")
    private boolean validate;

    @Option(names = "--dry-run",
            description = "Generate tests but do not modify the target project.")
    private boolean dryRun;

    @Option(names = "--max-fix-attempts",
            description = "Self-correction rounds per class (default: from configuration).")
    private Integer maxFixAttempts;

    @Option(names = "--with-external-context",
            description = "Fetch business/TMS context from configured MCP sources before generation.")
    private boolean withExternalContext;

    @Option(names = "--context-sources", split = ",",
            description = "Comma-separated MCP context source names to use (for example: confluence,zephyr).")
    private List<String> contextSources = List.of();

    @Option(names = "--context-query",
            description = "Override MCP search query template. Supports ${className}, ${fullyQualifiedName}, "
                    + "${packageName}, ${projectPath}, ${methods}.")
    private String contextQuery;

    public GenerateCommand(GenerateTestsUseCase generateTestsUseCase, AtfProperties properties) {
        this.generateTestsUseCase = generateTestsUseCase;
        this.properties = properties;
    }

    @Override
    public Integer call() {
        try {
            TestGenerationRequest request = TestGenerationRequest.builder(projectPath.toAbsolutePath())
                    .includedClasses(classes)
                    .llmProvider(llmProvider)
                    .validate(validate)
                    .dryRun(dryRun)
                    .maxFixAttempts(maxFixAttempts != null ? maxFixAttempts : properties.validation().maxFixAttempts())
                    .externalContext(externalContextRequest())
                    .progressListener(new ConsoleProgressListener())
                    .build();
            TestGenerationReport report = generateTestsUseCase.generateTests(request);
            printReport(report);
            return report.failed() == 0 ? 0 : 1;
        } catch (AtfException e) {
            System.err.println("ERROR: " + e.getMessage());
            return 2;
        }
    }

    private ExternalContextRequest externalContextRequest() {
        boolean enabled = withExternalContext || contextQuery != null || !contextSources.isEmpty();
        return new ExternalContextRequest(enabled, contextQuery, contextSources);
    }

    private void printReport(TestGenerationReport report) {
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.printf("  AutoTestForge report for %s%n", report.projectPath());
        System.out.println("=".repeat(100));
        System.out.printf("  %-45s %-22s %-8s %s%n", "CLASS", "STATUS", "LLM", "DETAILS");
        System.out.println("-".repeat(100));
        for (ClassGenerationResult result : report.results()) {
            String details = result.errorMessage() != null
                    ? firstLine(result.errorMessage())
                    : (result.writtenPath() != null ? result.writtenPath().toString() : "-");
            System.out.printf("  %-45s %-22s %-8d %s%n",
                    shorten(result.classFqn(), 45), result.status(), result.llmAttempts(), details);
        }
        System.out.println("-".repeat(100));
        System.out.println("  " + report.summary());
        System.out.println("=".repeat(100));
    }

    private String shorten(String value, int max) {
        return value.length() <= max ? value : "..." + value.substring(value.length() - max + 3);
    }

    private String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    /** Live per-class progress on stdout. */
    private static final class ConsoleProgressListener implements ProgressListener {

        private final AtomicInteger processed = new AtomicInteger();
        private volatile int total;

        @Override
        public void onScanCompleted(int discoveredClasses, int selectedClasses) {
            this.total = selectedClasses;
            System.out.printf("Scanned %d classes, %d selected for generation%n",
                    discoveredClasses, selectedClasses);
        }

        @Override
        public void onClassStarted(String classFqn) {
            System.out.printf("[%d/%d] %s ...%n", processed.get() + 1, total, classFqn);
        }

        @Override
        public void onClassFinished(ClassGenerationResult result) {
            processed.incrementAndGet();
            System.out.printf("[%d/%d] %s -> %s%n", processed.get(), total, result.classFqn(), result.status());
        }
    }
}
