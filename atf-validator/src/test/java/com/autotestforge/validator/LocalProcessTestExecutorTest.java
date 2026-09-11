package com.autotestforge.validator;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs({OS.LINUX, OS.MAC})
class LocalProcessTestExecutorTest {

    @TempDir
    Path projectRoot;

    private final JUnitXmlReportParser reportParser = new JUnitXmlReportParser();

    @Test
    @DisplayName("the project's own wrapper is preferred and a zero exit code means success")
    void runTests_shouldUseWrapperAndReportSuccess() throws IOException {
        writeWrapper("""
                #!/bin/sh
                echo "args: $@"
                echo "BUILD SUCCESS"
                exit 0
                """);
        LocalProcessTestExecutor executor = new LocalProcessTestExecutor(Duration.ofSeconds(30), reportParser);

        ValidationResult result = executor.runTests(projectRoot, BuildTool.MAVEN, "com.acme.OrderServiceTest", null);

        assertThat(result.success()).isTrue();
        assertThat(result.rawLog())
                .contains("-Dtest=OrderServiceTest")
                .contains("BUILD SUCCESS");
    }

    @Test
    @DisplayName("a failing build returns structured failures from the surefire report plus the log")
    void runTests_shouldReturnFailures_whenBuildFails() throws IOException {
        writeWrapper("""
                #!/bin/sh
                echo "[ERROR] Tests run: 1, Failures: 1"
                exit 1
                """);
        Path report = projectRoot.resolve("target/surefire-reports/TEST-com.acme.OrderServiceTest.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                <testsuite name="com.acme.OrderServiceTest" tests="1" failures="1">
                  <testcase classname="com.acme.OrderServiceTest" name="find_shouldWork">
                    <failure message="expected 1 but was 2">trace</failure>
                  </testcase>
                </testsuite>
                """);
        LocalProcessTestExecutor executor = new LocalProcessTestExecutor(Duration.ofSeconds(30), reportParser);

        ValidationResult result = executor.runTests(projectRoot, BuildTool.MAVEN, "com.acme.OrderServiceTest", null);

        assertThat(result.success()).isFalse();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).message()).isEqualTo("expected 1 but was 2");
        assertThat(result.rawLog()).contains("Failures: 1");
    }

    @Test
    @DisplayName("a hanging build is killed when the timeout elapses instead of blocking forever")
    void runTests_shouldEnforceTimeout_whenBuildHangs() throws IOException {
        writeWrapper("""
                #!/bin/sh
                echo "compiling..."
                sleep 60
                """);
        LocalProcessTestExecutor executor = new LocalProcessTestExecutor(Duration.ofMillis(700), reportParser);

        long started = System.nanoTime();
        assertThatThrownBy(() -> executor.runTests(projectRoot, BuildTool.MAVEN, "com.acme.SlowTest", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("timed out")
                .hasMessageContaining("compiling...");
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMillis).isLessThan(20_000);
    }

    @Test
    @DisplayName("very long output is kept bounded (head and tail preserved)")
    void runTests_shouldBoundCapturedOutput() throws IOException {
        writeWrapper("""
                #!/bin/sh
                echo "FIRST LINE"
                i=0
                while [ $i -lt 40000 ]; do echo "noise line number $i with some padding text"; i=$((i+1)); done
                echo "LAST LINE"
                exit 0
                """);
        LocalProcessTestExecutor executor = new LocalProcessTestExecutor(Duration.ofMinutes(2), reportParser);

        ValidationResult result = executor.runTests(projectRoot, BuildTool.MAVEN, "com.acme.NoisyTest", null);

        assertThat(result.rawLog())
                .startsWith("FIRST LINE")
                .contains("[output truncated]")
                .endsWith("LAST LINE\n");
        assertThat(result.rawLog().length()).isLessThan(260_000);
    }

    private void writeWrapper(String script) throws IOException {
        Path wrapper = projectRoot.resolve("mvnw");
        Files.writeString(wrapper, script);
        Files.setPosixFilePermissions(wrapper, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    }
}
