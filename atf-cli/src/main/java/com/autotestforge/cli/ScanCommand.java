package com.autotestforge.cli;

import com.autotestforge.core.domain.ClassTarget;
import com.autotestforge.core.domain.ScanPreview;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.exception.AtfException;
import com.autotestforge.core.port.in.ScanProjectUseCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The {@code atf scan} command: lists what {@code generate} would do for a
 * project without calling any LLM or touching the project. Useful for picking
 * classes and estimating cost before a real run.
 */
@Component
@Command(name = "scan",
        mixinStandardHelpOptions = true,
        description = "Scan a Java project and list the classes tests would be generated for (no LLM calls).")
public class ScanCommand implements Callable<Integer> {

    private final ScanProjectUseCase scanProjectUseCase;
    private final PrintStream out;
    private final PrintStream err;

    @Option(names = {"--project-path", "-p"}, required = true,
            description = "Root directory of the target Java project (Maven or Gradle).")
    Path projectPath;

    @Option(names = {"--classes", "-c"}, split = ",",
            description = "Class filter, same syntax as for generate (wildcards allowed).")
    List<String> classes = List.of();

    @Option(names = {"--exclude", "-x"}, split = ",",
            description = "Classes to leave out, same syntax as for generate.")
    List<String> excludes = List.of();

    @Option(names = "--all",
            description = "Also list types that are not eligible, with the reason.")
    boolean showAll;

    @Autowired
    public ScanCommand(ScanProjectUseCase scanProjectUseCase) {
        this(scanProjectUseCase, System.out, System.err);
    }

    ScanCommand(ScanProjectUseCase scanProjectUseCase, PrintStream out, PrintStream err) {
        this.scanProjectUseCase = scanProjectUseCase;
        this.out = out;
        this.err = err;
    }

    @Override
    public Integer call() {
        try {
            ScanPreview preview = scanProjectUseCase.previewTargets(TestGenerationRequest
                    .builder(projectPath.toAbsolutePath().normalize())
                    .includedClasses(classes)
                    .excludedClasses(excludes)
                    .build());
            print(preview);
            return 0;
        } catch (AtfException e) {
            err.println("ERROR: " + e.getMessage());
            return 2;
        }
    }

    private void print(ScanPreview preview) {
        out.printf("Project: %s (build tool: %s)%n", preview.projectPath(),
                preview.buildTool() == null ? "unknown" : preview.buildTool());
        out.printf("  %-55s %-9s %-8s %-6s %s%n", "CLASS", "KIND", "METHODS", "MOCKS", "NOTES");
        out.println("-".repeat(110));
        for (ClassTarget target : preview.classes()) {
            if (!target.eligible() && !showAll) {
                continue;
            }
            out.printf("  %-55s %-9s %-8d %-6d %s%n",
                    shorten(target.classFqn(), 55),
                    target.kind().name().toLowerCase(),
                    target.publicMethods().size(),
                    target.collaborators().size(),
                    notes(target));
        }
        out.println("-".repeat(110));
        out.printf("  %d type(s) discovered, %d eligible for generation, %d already have a test%n",
                preview.classes().size(), preview.eligibleCount(), preview.withExistingTests());
        if (!showAll && preview.eligibleCount() < preview.classes().size()) {
            out.println("  (use --all to see skipped types and the reasons)");
        }
    }

    private String notes(ClassTarget target) {
        if (!target.eligible()) {
            return "skipped: " + target.skipReason();
        }
        StringBuilder notes = new StringBuilder();
        if (target.springStereotype() != null) {
            notes.append('@').append(target.springStereotype()).append(' ');
        }
        if (target.hasExistingTest()) {
            notes.append("has test (needs --overwrite)");
        }
        return notes.toString().strip();
    }

    private String shorten(String value, int max) {
        return value.length() <= max ? value : "..." + value.substring(value.length() - max + 3);
    }
}
