package com.autotestforge.writer;

import java.util.List;

/**
 * A test-scope dependency the generated tests rely on.
 *
 * @param groupId    Maven group id
 * @param artifactId Maven artifact id
 * @param version    version used when the target project has no BOM managing it
 */
public record TestDependency(String groupId, String artifactId, String version) {

    /** The toolkit every generated test is written against. */
    public static final List<TestDependency> REQUIRED = List.of(
            new TestDependency("org.junit.jupiter", "junit-jupiter", "5.10.2"),
            new TestDependency("org.mockito", "mockito-core", "5.14.2"),
            new TestDependency("org.mockito", "mockito-junit-jupiter", "5.14.2"),
            new TestDependency("org.assertj", "assertj-core", "3.26.3"));

    public String coordinates() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
