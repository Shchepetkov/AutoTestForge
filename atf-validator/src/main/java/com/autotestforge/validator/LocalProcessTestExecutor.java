package com.autotestforge.validator;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.TestFailure;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.ValidationException;
import com.autotestforge.core.port.out.TestValidatorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fallback {@link TestValidatorPort} adapter used when Docker is not
 * available: runs the build tool as a local process, preferring the project's
 * own wrapper ({@code mvnw} / {@code gradlew}) over binaries from {@code PATH}.
 * Output is drained concurrently so the timeout is enforced even when the build
 * hangs, and only a bounded tail of the log is kept in memory.
 */
public class LocalProcessTestExecutor implements TestValidatorPort {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessTestExecutor.class);

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final int MAX_OUTPUT_CHARS = 200_000;

    private final Duration timeout;
    private final JUnitXmlReportParser reportParser;

    public LocalProcessTestExecutor(Duration timeout, JUnitXmlReportParser reportParser) {
        this.timeout = timeout;
        this.reportParser = reportParser;
    }

    @Override
    public ValidationResult runTests(Path projectRoot, BuildTool buildTool, String testClassFqn, Path testFile) {
        List<String> command = buildCommand(projectRoot, buildTool, testClassFqn, testFile);
        log.info("Running local process: {}", String.join(" ", command));
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new ValidationException("Failed to start local test process '" + command.get(0)
                    + "'. Is the build tool installed or does the project provide a wrapper?", e);
        }

        BoundedOutputCapture capture = new BoundedOutputCapture(process.getInputStream(), MAX_OUTPUT_CHARS);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new ValidationException("Test run timed out after " + timeout + " for " + testClassFqn
                        + ". Last output:\n" + tail(capture.text()));
            }
            String output = capture.awaitOutput(10, TimeUnit.SECONDS);
            if (process.exitValue() == 0) {
                log.info("Local run passed for {}", testClassFqn);
                return ValidationResult.success(output);
            }
            List<TestFailure> failures = reportParser.parseFailures(projectRoot, testClassFqn);
            log.info("Local run failed for {} (exit={}, {} structured failures)",
                    testClassFqn, process.exitValue(), failures.size());
            return ValidationResult.failure(failures, output);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ValidationException("Interrupted while waiting for the local test run", e);
        }
    }

    private List<String> buildCommand(Path projectRoot, BuildTool buildTool, String testClassFqn, Path testFile) {
        List<String> command = new ArrayList<>();
        command.add(executable(projectRoot, buildTool));
        command.addAll(TestCommand.arguments(buildTool, testClassFqn,
                TestCommand.moduleOf(projectRoot, testFile).orElse(null)));
        return command;
    }

    private String executable(Path projectRoot, BuildTool buildTool) {
        String wrapper = buildTool == BuildTool.MAVEN
                ? (WINDOWS ? "mvnw.cmd" : "mvnw")
                : (WINDOWS ? "gradlew.bat" : "gradlew");
        Path wrapperPath = projectRoot.resolve(wrapper);
        if (Files.exists(wrapperPath)) {
            return wrapperPath.toAbsolutePath().toString();
        }
        String binary = buildTool == BuildTool.MAVEN ? "mvn" : "gradle";
        return WINDOWS ? binary + ".cmd" : binary;
    }

    private static String tail(String output) {
        return output.length() <= 2_000 ? output : output.substring(output.length() - 2_000);
    }
}
