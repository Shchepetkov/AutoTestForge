package com.autotestforge.validator;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalProcessTestExecutorTest {
    @Test
    @Timeout(10)
    void stopsSilentProcessWithinTimeout() {
        var executor = new LocalProcessTestExecutor(Duration.ofMillis(500), new JUnitXmlReportParser());
        assertThatThrownBy(() -> executor.runCommand(Path.of("."), "example.ExampleTest", command("sleep")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("timed out");
    }

    @Test
    @Timeout(15)
    void drainsLargeOutputAndKeepsBoundedTail() {
        var executor = new LocalProcessTestExecutor(Duration.ofSeconds(10), new JUnitXmlReportParser());
        var result = executor.runCommand(Path.of("."), "example.ExampleTest", command("output"));
        assertThat(result.success()).isTrue();
        assertThat(result.rawLog()).startsWith("[Earlier test output truncated]").endsWith("THE_END");
        assertThat(result.rawLog().length()).isLessThan(270_000);
    }

    @Test
    void selectsQualifiedClassAndRejectsShellCharacters() {
        assertThat(TestCommand.arguments(BuildTool.MAVEN, "example.ExampleTest"))
                .contains("-Dtest=example.ExampleTest");
        assertThatThrownBy(() -> TestCommand.arguments(BuildTool.MAVEN, "ExampleTest&whoami"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<String> command(String mode) {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), OutputProcess.class.getName(), mode);
    }

    public static class OutputProcess {
        public static void main(String[] args) throws InterruptedException {
            if (args[0].equals("sleep")) {
                Thread.sleep(60_000);
            } else {
                System.out.print("x".repeat(400_000));
                System.out.print("THE_END");
            }
        }
    }
}
