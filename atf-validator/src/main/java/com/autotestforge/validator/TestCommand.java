package com.autotestforge.validator;

import com.autotestforge.core.domain.BuildTool;

import java.util.List;
import javax.lang.model.SourceVersion;

/** Builds the build-tool command line that runs a single test class. */
final class TestCommand {

    private TestCommand() {
    }

    static List<String> arguments(BuildTool buildTool, String testClassFqn) {
        if (testClassFqn == null || !SourceVersion.isName(testClassFqn)) {
            throw new IllegalArgumentException("Invalid test class name");
        }
        if (buildTool == BuildTool.MAVEN) {
            return List.of("-B", "-q", "test",
                    "-Dtest=" + testClassFqn,
                    "-Dsurefire.failIfNoSpecifiedTests=false");
        }
        return List.of("test", "--tests", testClassFqn, "--console=plain");
    }
}
