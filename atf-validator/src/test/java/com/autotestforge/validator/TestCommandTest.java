package com.autotestforge.validator;

import com.autotestforge.core.domain.BuildTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestCommandTest {

    @Test
    @DisplayName("Maven: single class is selected by simple name and missing tests never fail the build")
    void arguments_shouldBuildMavenCommand_forSingleModule() {
        assertThat(TestCommand.arguments(BuildTool.MAVEN, "com.acme.OrderServiceTest"))
                .containsExactly("-B", "-ntp", "-q", "test", "-Dtest=OrderServiceTest",
                        "-Dsurefire.failIfNoSpecifiedTests=false", "-DfailIfNoTests=false");
    }

    @Test
    @DisplayName("Maven multi-module: only the owning module (and what it needs) is built")
    void arguments_shouldRestrictMavenBuildToModule() {
        assertThat(TestCommand.arguments(BuildTool.MAVEN, "com.acme.OrderServiceTest",
                Path.of("services", "orders")))
                .containsSequence("-pl", "services/orders", "-am", "test");
    }

    @Test
    @DisplayName("Gradle: the test task of the owning subproject is addressed with the Gradle path notation")
    void arguments_shouldBuildGradleCommand() {
        assertThat(TestCommand.arguments(BuildTool.GRADLE_KOTLIN, "com.acme.OrderServiceTest"))
                .containsExactly("test", "--tests", "com.acme.OrderServiceTest", "--console=plain");
        assertThat(TestCommand.arguments(BuildTool.GRADLE_GROOVY, "com.acme.OrderServiceTest",
                Path.of("services", "orders")))
                .startsWith(":services:orders:test");
    }

    @Test
    @DisplayName("module detection: derived from the test file, empty for single-module projects")
    void moduleOf_shouldDeriveModuleFromTestFile() {
        Path root = Path.of("/repo");

        assertThat(TestCommand.moduleOf(root,
                Path.of("/repo/services/orders/src/test/java/com/acme/OrderServiceTest.java")))
                .contains(Path.of("services/orders"));
        assertThat(TestCommand.moduleOf(root, Path.of("/repo/src/test/java/com/acme/OrderServiceTest.java")))
                .isEmpty();
        assertThat(TestCommand.moduleOf(root, Path.of("/elsewhere/src/test/java/X.java"))).isEmpty();
        assertThat(TestCommand.moduleOf(root, null)).isEmpty();
    }
}
