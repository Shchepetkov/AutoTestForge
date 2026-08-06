package com.autotestforge.validator;

import com.autotestforge.core.domain.BuildTool;

import java.util.List;

/** Builds the build-tool command line that runs a single test class. */
final class TestCommand {

    private TestCommand() {
    }

    static List<String> arguments(BuildTool buildTool, String testClassFqn) {
        if (buildTool == BuildTool.MAVEN) {
            String simpleName = testClassFqn.substring(testClassFqn.lastIndexOf('.') + 1);
            return List.of("-B", "-q", "test",
                    "-Dtest=" + simpleName,
                    "-Dsurefire.failIfNoSpecifiedTests=false");
        }
        return List.of("test", "--tests", testClassFqn, "--console=plain");
    }
}
