package com.autotestforge.writer;

import com.autotestforge.core.domain.BuildTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GradleBuildUpdaterTest {

    @TempDir
    Path projectRoot;

    private final GradleBuildUpdater updater = new GradleBuildUpdater();

    @Test
    @DisplayName("Groovy DSL: missing dependencies are inserted into the existing dependencies block")
    void ensureTestDependencies_shouldInsertIntoExistingBlock_groovy() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }

                dependencies {
                    implementation 'com.google.guava:guava:33.0.0-jre'
                }
                """);

        updater.ensureTestDependencies(projectRoot, BuildTool.GRADLE_GROOVY);

        String updated = Files.readString(projectRoot.resolve("build.gradle"));
        assertThat(updated)
                .contains("testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'")
                .contains("testImplementation 'org.mockito:mockito-core:5.14.2'")
                .contains("testImplementation 'org.assertj:assertj-core:3.26.3'")
                .contains("implementation 'com.google.guava:guava:33.0.0-jre'");
        assertThat(updated.indexOf("dependencies {"))
                .isLessThan(updated.indexOf("testImplementation 'org.junit.jupiter"));
    }

    @Test
    @DisplayName("Kotlin DSL: a dependencies block is appended when none exists")
    void ensureTestDependencies_shouldAppendBlock_whenNoneExists_kotlin() throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle.kts"), """
                plugins {
                    java
                }
                """);

        updater.ensureTestDependencies(projectRoot, BuildTool.GRADLE_KOTLIN);

        String updated = Files.readString(projectRoot.resolve("build.gradle.kts"));
        assertThat(updated)
                .contains("dependencies {")
                .contains("testImplementation(\"org.junit.jupiter:junit-jupiter:5.10.2\")");
    }

    @Test
    @DisplayName("already declared coordinates are left untouched")
    void ensureTestDependencies_shouldSkipExistingDependencies() throws IOException {
        String original = """
                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.0'
                    testImplementation 'org.mockito:mockito-core:5.15.0'
                    testImplementation 'org.mockito:mockito-junit-jupiter:5.15.0'
                    testImplementation 'org.assertj:assertj-core:3.27.0'
                }
                """;
        Files.writeString(projectRoot.resolve("build.gradle"), original);

        updater.ensureTestDependencies(projectRoot, BuildTool.GRADLE_GROOVY);

        assertThat(Files.readString(projectRoot.resolve("build.gradle"))).isEqualTo(original);
    }
}
