package com.autotestforge.validator;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.TestFailure;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.ValidationException;
import com.autotestforge.core.port.out.TestValidatorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link TestValidatorPort} adapter that executes generated tests inside a
 * disposable Docker container (Testcontainers), fully isolating the host from
 * the target project's build. The local Maven/Gradle cache directory is
 * mounted so consecutive runs do not re-download dependencies.
 */
public class DockerTestExecutor implements TestValidatorPort {

    private static final Logger log = LoggerFactory.getLogger(DockerTestExecutor.class);

    private static final String WORKSPACE = "/workspace";

    private final String mavenImage;
    private final String gradleImage;
    private final Path cacheDir;
    private final JUnitXmlReportParser reportParser;

    public DockerTestExecutor(String mavenImage, String gradleImage, Path cacheDir,
                              JUnitXmlReportParser reportParser) {
        this.mavenImage = mavenImage;
        this.gradleImage = gradleImage;
        this.cacheDir = cacheDir;
        this.reportParser = reportParser;
    }

    @Override
    public ValidationResult runTests(Path projectRoot, BuildTool buildTool, String testClassFqn, Path testFile) {
        String image = buildTool == BuildTool.MAVEN ? mavenImage : gradleImage;
        List<String> command = buildCommand(buildTool, testClassFqn,
                TestCommand.moduleOf(projectRoot, testFile).orElse(null));
        log.info("Running {} in container {} for {}", String.join(" ", command), image, projectRoot);

        try (GenericContainer<?> container = new GenericContainer<>(image)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sleep"))
                .withCommand("infinity")
                .withFileSystemBind(projectRoot.toAbsolutePath().toString(), WORKSPACE, BindMode.READ_WRITE)
                .withFileSystemBind(ensureCacheDir(buildTool).toString(), cacheMountPoint(buildTool),
                        BindMode.READ_WRITE)) {
            container.start();
            Container.ExecResult result = container.execInContainer(ExecConfig.builder()
                    .workDir(WORKSPACE)
                    .command(command.toArray(String[]::new))
                    .build());
            String output = result.getStdout() + System.lineSeparator() + result.getStderr();
            if (result.getExitCode() == 0) {
                log.info("Container run passed for {}", testClassFqn);
                return ValidationResult.success(output);
            }
            List<TestFailure> failures = reportParser.parseFailures(projectRoot, testClassFqn);
            log.info("Container run failed for {} (exit={}, {} structured failures)",
                    testClassFqn, result.getExitCode(), failures.size());
            return ValidationResult.failure(failures, output);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ValidationException("Failed to execute tests in Docker container", e);
        }
    }

    private List<String> buildCommand(BuildTool buildTool, String testClassFqn, Path module) {
        List<String> command = new ArrayList<>();
        command.add(buildTool == BuildTool.MAVEN ? "mvn" : "gradle");
        command.addAll(TestCommand.arguments(buildTool, testClassFqn, module));
        return command;
    }

    private Path ensureCacheDir(BuildTool buildTool) {
        Path dir = cacheDir.resolve(buildTool == BuildTool.MAVEN ? "m2" : "gradle");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ValidationException("Failed to create cache directory " + dir, e);
        }
        return dir.toAbsolutePath();
    }

    private String cacheMountPoint(BuildTool buildTool) {
        return buildTool == BuildTool.MAVEN ? "/root/.m2" : "/home/gradle/.gradle";
    }
}
