package com.autotestforge.validator;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.TestFailure;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.ValidationException;
import com.autotestforge.core.port.out.TestValidatorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 */
public class LocalProcessTestExecutor implements TestValidatorPort {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessTestExecutor.class);

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private final Duration timeout;
    private final JUnitXmlReportParser reportParser;

    public LocalProcessTestExecutor(Duration timeout, JUnitXmlReportParser reportParser) {
        this.timeout = timeout;
        this.reportParser = reportParser;
    }

    @Override
    public ValidationResult runTests(Path projectRoot, BuildTool buildTool, String testClassFqn) {
        List<String> command = buildCommand(projectRoot, buildTool, testClassFqn);
        log.info("Running local process: {}", String.join(" ", command));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ValidationException("Test run timed out after " + timeout + " for " + testClassFqn);
            }
            if (process.exitValue() == 0) {
                log.info("Local run passed for {}", testClassFqn);
                return ValidationResult.success(output);
            }
            List<TestFailure> failures = reportParser.parseFailures(projectRoot, testClassFqn);
            log.info("Local run failed for {} (exit={}, {} structured failures)",
                    testClassFqn, process.exitValue(), failures.size());
            return ValidationResult.failure(failures, output);
        } catch (IOException e) {
            throw new ValidationException("Failed to start local test process. Is the build tool installed "
                    + "or does the project provide a wrapper?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ValidationException("Interrupted while waiting for the local test run", e);
        }
    }

    private List<String> buildCommand(Path projectRoot, BuildTool buildTool, String testClassFqn) {
        List<String> command = new ArrayList<>();
        command.add(executable(projectRoot, buildTool));
        command.addAll(TestCommand.arguments(buildTool, testClassFqn));
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
}
