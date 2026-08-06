package com.autotestforge.writer;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.exception.TestWriteException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildToolAdapterTest {

    @TempDir
    Path projectRoot;

    private final BuildToolAdapter adapter =
            new BuildToolAdapter(new MavenPomUpdater(), new GradleBuildUpdater());

    @Test
    @DisplayName("build tool detection: pom.xml wins, then Kotlin DSL, then Groovy DSL")
    void detect_shouldRecognizeSupportedBuildTools() throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        assertThat(adapter.detect(projectRoot)).isEqualTo(BuildTool.MAVEN);

        Files.delete(projectRoot.resolve("pom.xml"));
        Files.writeString(projectRoot.resolve("build.gradle.kts"), "");
        assertThat(adapter.detect(projectRoot)).isEqualTo(BuildTool.GRADLE_KOTLIN);

        Files.delete(projectRoot.resolve("build.gradle.kts"));
        Files.writeString(projectRoot.resolve("build.gradle"), "");
        assertThat(adapter.detect(projectRoot)).isEqualTo(BuildTool.GRADLE_GROOVY);
    }

    @Test
    @DisplayName("a project without a supported build file is rejected")
    void detect_shouldThrow_whenNoBuildFilePresent() {
        assertThatThrownBy(() -> adapter.detect(projectRoot))
                .isInstanceOf(TestWriteException.class)
                .hasMessageContaining("No supported build file");
    }
}
