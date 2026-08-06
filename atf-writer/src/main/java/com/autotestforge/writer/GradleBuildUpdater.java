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
 */
public class GradleBuildUpdater {

    private static final Logger log = LoggerFactory.getLogger(GradleBuildUpdater.class);

    private static final Pattern DEPENDENCIES_BLOCK = Pattern.compile("(?m)^\\s*dependencies\\s*\\{");

    public void ensureTestDependencies(Path projectRoot, BuildTool buildTool) {
        Path buildFile = projectRoot.resolve(
                buildTool == BuildTool.GRADLE_KOTLIN ? "build.gradle.kts" : "build.gradle");
        String content = readBuildFile(buildFile);

        List<String> newLines = new ArrayList<>();
        for (TestDependency required : TestDependency.REQUIRED) {
            if (content.contains(required.groupId() + ":" + required.artifactId())) {
                continue;
            }
            newLines.add(dependencyLine(required, buildTool));
            log.info("Adding test dependency {} to {}", required.coordinates(), buildFile);
        }
        if (newLines.isEmpty()) {
            log.info("All required test dependencies already declared in {}", buildFile);
            return;
        }

        String insertion = String.join(System.lineSeparator(), newLines);
        Matcher matcher = DEPENDENCIES_BLOCK.matcher(content);
        String updated = matcher.find()
                ? content.substring(0, matcher.end()) + System.lineSeparator() + insertion
                        + content.substring(matcher.end())
                : content + System.lineSeparator() + "dependencies {" + System.lineSeparator()
                        + insertion + System.lineSeparator() + "}" + System.lineSeparator();

        try {
            Files.writeString(buildFile, updated);
        } catch (IOException e) {
            throw new TestWriteException("Failed to update " + buildFile, e);
        }
    }

    private String dependencyLine(TestDependency dependency, BuildTool buildTool) {
        return buildTool == BuildTool.GRADLE_KOTLIN
                ? "    testImplementation(\"" + dependency.coordinates() + "\")"
                : "    testImplementation '" + dependency.coordinates() + "'";
    }

    private String readBuildFile(Path buildFile) {
        try {
            return Files.readString(buildFile);
        } catch (IOException e) {
            throw new TestWriteException("Failed to read " + buildFile, e);
        }
    }
}
