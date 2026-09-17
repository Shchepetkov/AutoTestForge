package com.autotestforge.validator;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.TestFailure;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.ValidationException;
import com.autotestforge.core.port.out.TestValidatorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;

/**
 * Fallback {@link TestValidatorPort} adapter used when Docker is not
 * available: runs the build tool as a local process, preferring the project's
 * own wrapper ({@code mvnw} / {@code gradlew}) over binaries from {@code PATH}.
 */
public class LocalProcessTestExecutor implements TestValidatorPort {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessTestExecutor.class);

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    private final Duration timeout;
    private final JUnitXmlReportParser reportParser;

    public LocalProcessTestExecutor(Duration timeout, JUnitXmlReportParser reportParser) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Test timeout must be positive");
        }
        this.timeout = timeout;
        this.reportParser = reportParser;
    }

    @Override
    public ValidationResult runTests(Path projectRoot, BuildTool buildTool, String testClassFqn) {
        List<String> command = buildCommand(projectRoot, buildTool, testClassFqn);
        log.info("Running local process: {}", String.join(" ", command));
        return runCommand(projectRoot, testClassFqn, command);
    }

    ValidationResult runCommand(Path projectRoot, String testClassFqn, List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            InputStream stream = process.getInputStream();
            FutureTask<String> outputReader = new FutureTask<>(() -> readOutputTail(stream));
            Thread reader = new Thread(outputReader, "atf-test-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ValidationException("Test run timed out after " + timeout + " for " + testClassFqn);
            }
            String output = outputReader.get(5, TimeUnit.SECONDS);
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
        } catch (ExecutionException | TimeoutException e) {
            throw new ValidationException("Failed to collect local test output", e);
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                try {
                    process.getInputStream().close();
                } catch (IOException ignored) {
                    // The process may already have closed the pipe.
                }
            }
        }
    }

    /** Drain concurrently so a full pipe never blocks the timeout; retain only the log tail. */
    private String readOutputTail(InputStream stream) throws IOException {
        byte[] tail = new byte[MAX_OUTPUT_BYTES];
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        try (stream) {
            while ((count = stream.read(buffer)) != -1) {
                int offset = (int) (total % tail.length);
                int first = Math.min(count, tail.length - offset);
                System.arraycopy(buffer, 0, tail, offset, first);
                System.arraycopy(buffer, first, tail, 0, count - first);
                total += count;
            }
        }
        int length = (int) Math.min(total, tail.length);
        byte[] ordered = new byte[length];
        int start = total > tail.length ? (int) (total % tail.length) : 0;
        int first = Math.min(length, tail.length - start);
        System.arraycopy(tail, start, ordered, 0, first);
        System.arraycopy(tail, 0, ordered, first, length - first);
        return (total > tail.length ? "[Earlier test output truncated]\n" : "")
                + new String(ordered, StandardCharsets.UTF_8);
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
