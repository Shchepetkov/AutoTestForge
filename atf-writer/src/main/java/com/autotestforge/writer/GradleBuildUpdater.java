package com.autotestforge.writer;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.exception.TestWriteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds missing test dependencies to {@code build.gradle} (Groovy DSL) or
 * {@code build.gradle.kts} (Kotlin DSL). The build file is edited textually:
 * new {@code testImplementation} lines are inserted at the top of the existing
 * {@code dependencies} block, or a new block is appended when none exists.
 * <p>
 * Gradle's default {@code test} task only runs JUnit 5 when
 * {@code useJUnitPlatform()} is configured and (since Gradle 9) the platform
 * launcher is on the test runtime classpath; both are added when absent so the
 * generated tests actually execute.
 */
public class GradleBuildUpdater {

    private static final Logger log = LoggerFactory.getLogger(GradleBuildUpdater.class);

    private static final Pattern DEPENDENCIES_BLOCK = Pattern.compile("(?m)^\\s*dependencies\\s*\\{");
    private static final Pattern USE_JUNIT_PLATFORM = Pattern.compile("useJUnitPlatform\\s*\\(");
    private static final Pattern TEST_SUITES_DSL = Pattern.compile("useJUnitJupiter\\s*\\(");
    private static final String LAUNCHER = "org.junit.platform:junit-platform-launcher";

    public void ensureTestDependencies(Path projectRoot, BuildTool buildTool) {
        Path buildFile = projectRoot.resolve(
                buildTool == BuildTool.GRADLE_KOTLIN ? "build.gradle.kts" : "build.gradle");
        String content = readBuildFile(buildFile);
        String updated = content;

        List<String> newLines = new ArrayList<>();
        for (TestDependency required : TestDependency.REQUIRED) {
            if (content.contains(required.groupId() + ":" + required.artifactId())) {
                continue;
            }
            newLines.add(dependencyLine("testImplementation", required.coordinates(), buildTool));
            log.info("Adding test dependency {} to {}", required.coordinates(), buildFile);
        }
        if (!content.contains(LAUNCHER)) {
            newLines.add(dependencyLine("testRuntimeOnly", LAUNCHER, buildTool));
            log.info("Adding {} to {}", LAUNCHER, buildFile);
        }
        if (!newLines.isEmpty()) {
            updated = insertDependencies(updated, newLines);
        }

        if (!USE_JUNIT_PLATFORM.matcher(updated).find() && !TEST_SUITES_DSL.matcher(updated).find()) {
            updated = updated.stripTrailing() + System.lineSeparator() + System.lineSeparator()
                    + junitPlatformBlock(buildTool) + System.lineSeparator();
            log.info("Enabling the JUnit Platform for the test task in {}", buildFile);
        }

        if (updated.equals(content)) {
            log.info("All required test dependencies already declared in {}", buildFile);
            return;
        }
        try {
            Files.writeString(buildFile, updated);
        } catch (IOException e) {
            throw new TestWriteException("Failed to update " + buildFile, e);
        }
    }

    private String insertDependencies(String content, List<String> newLines) {
        String insertion = String.join(System.lineSeparator(), newLines);
        Matcher matcher = DEPENDENCIES_BLOCK.matcher(content);
        return matcher.find()
                ? content.substring(0, matcher.end()) + System.lineSeparator() + insertion
                        + content.substring(matcher.end())
                : content.stripTrailing() + System.lineSeparator() + System.lineSeparator() + "dependencies {"
                        + System.lineSeparator() + insertion + System.lineSeparator() + "}" + System.lineSeparator();
    }

    private String dependencyLine(String configuration, String coordinates, BuildTool buildTool) {
        return buildTool == BuildTool.GRADLE_KOTLIN
                ? "    " + configuration + "(\"" + coordinates + "\")"
                : "    " + configuration + " '" + coordinates + "'";
    }

    private String junitPlatformBlock(BuildTool buildTool) {
        return buildTool == BuildTool.GRADLE_KOTLIN
                ? "tasks.withType<Test> {" + System.lineSeparator() + "    useJUnitPlatform()"
                        + System.lineSeparator() + "}"
                : "tasks.withType(Test).configureEach {" + System.lineSeparator() + "    useJUnitPlatform()"
                        + System.lineSeparator() + "}";
    }

    private String readBuildFile(Path buildFile) {
        try {
            return Files.readString(buildFile);
        } catch (IOException e) {
            throw new TestWriteException("Failed to read " + buildFile, e);
        }
    }
}
