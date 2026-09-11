package com.autotestforge.validator;

import com.autotestforge.core.domain.BuildTool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** Builds the build-tool command line that runs a single test class. */
final class TestCommand {

    private static final Path TEST_SOURCE_SUFFIX = Path.of("src", "test", "java");

    private TestCommand() {
    }

    static List<String> arguments(BuildTool buildTool, String testClassFqn) {
        return arguments(buildTool, testClassFqn, null);
    }

    /**
     * @param module project-relative path of the module owning the test, or null
     *               for single-module projects / unknown location
     */
    static List<String> arguments(BuildTool buildTool, String testClassFqn, Path module) {
        if (buildTool == BuildTool.MAVEN) {
            String simpleName = testClassFqn.substring(testClassFqn.lastIndexOf('.') + 1);
            List<String> args = new ArrayList<>(List.of("-B", "-ntp", "-q"));
            if (module != null) {
                args.add("-pl");
                args.add(toUnixPath(module));
                args.add("-am");
            }
            args.addAll(List.of("test",
                    "-Dtest=" + simpleName,
                    "-Dsurefire.failIfNoSpecifiedTests=false",
                    "-DfailIfNoTests=false"));
            return args;
        }
        String task = module == null ? "test" : ":" + toUnixPath(module).replace('/', ':') + ":test";
        return List.of(task, "--tests", testClassFqn, "--console=plain");
    }

    /**
     * Derives the module directory (relative to the project root) from the
     * location of the written test file. Empty for single-module projects.
     */
    static Optional<Path> moduleOf(Path projectRoot, Path testFile) {
        if (testFile == null || projectRoot == null) {
            return Optional.empty();
        }
        Path current = testFile.toAbsolutePath().normalize();
        while (current != null && !current.endsWith(TEST_SOURCE_SUFFIX)) {
            current = current.getParent();
        }
        if (current == null || current.getNameCount() < 3) {
            return Optional.empty();
        }
        Path moduleRoot = current.getParent().getParent().getParent();
        Path root = projectRoot.toAbsolutePath().normalize();
        if (moduleRoot == null || moduleRoot.equals(root) || !moduleRoot.startsWith(root)) {
            return Optional.empty();
        }
        return Optional.of(root.relativize(moduleRoot));
    }

    private static String toUnixPath(Path path) {
        return StreamSupport.stream(path.spliterator(), false)
                .map(Path::toString)
                .collect(Collectors.joining("/"));
    }
}
