package com.autotestforge.core.port.out;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.exception.TestWriteException;

import java.nio.file.Path;

/** Driven port: build-system awareness of the target project. */
public interface BuildToolPort {

    /**
     * Detects the build tool used by the target project.
     *
     * @throws TestWriteException when no supported build file is found
     */
    BuildTool detect(Path projectRoot);

    /**
     * Ensures the test-scope dependencies required by generated tests
     * (JUnit 5, Mockito, AssertJ) are declared, adding missing ones.
     */
    void ensureTestDependencies(Path projectRoot, BuildTool buildTool);
}
