package com.autotestforge.writer;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.exception.TestWriteException;
import com.autotestforge.core.port.out.BuildToolPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link BuildToolPort} adapter: detects Maven / Gradle by the build files in
 * the project root and delegates dependency maintenance to the matching updater.
 */
public class BuildToolAdapter implements BuildToolPort {

    private static final Logger log = LoggerFactory.getLogger(BuildToolAdapter.class);

    private final MavenPomUpdater mavenPomUpdater;
    private final GradleBuildUpdater gradleBuildUpdater;

    public BuildToolAdapter(MavenPomUpdater mavenPomUpdater, GradleBuildUpdater gradleBuildUpdater) {
        this.mavenPomUpdater = mavenPomUpdater;
        this.gradleBuildUpdater = gradleBuildUpdater;
    }

    @Override
    public BuildTool detect(Path projectRoot) {
        if (Files.exists(projectRoot.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        if (Files.exists(projectRoot.resolve("build.gradle.kts"))) {
            return BuildTool.GRADLE_KOTLIN;
        }
        if (Files.exists(projectRoot.resolve("build.gradle"))) {
            return BuildTool.GRADLE_GROOVY;
        }
        throw new TestWriteException("No supported build file (pom.xml, build.gradle, build.gradle.kts) found in "
                + projectRoot);
    }

    @Override
    public void ensureTestDependencies(Path projectRoot, BuildTool buildTool) {
        log.info("Ensuring test dependencies in {} ({})", projectRoot, buildTool);
        if (buildTool == BuildTool.MAVEN) {
            mavenPomUpdater.ensureTestDependencies(projectRoot);
        } else {
            gradleBuildUpdater.ensureTestDependencies(projectRoot, buildTool);
        }
    }
}
