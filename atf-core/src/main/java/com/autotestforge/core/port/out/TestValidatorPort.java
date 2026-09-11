package com.autotestforge.core.port.out;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.ValidationException;

import java.nio.file.Path;

/** Driven port: executes generated tests in an isolated environment. */
public interface TestValidatorPort {

    /**
     * Runs a single generated test class inside the isolation sandbox
     * (Docker container when available).
     *
     * @param projectRoot  root of the target project (where the build is launched)
     * @param testClassFqn fully qualified name of the test class to run
     * @param testFile     location of the written test; lets adapters restrict a
     *                     multi-module build to the owning module (may be null)
     * @throws ValidationException when the environment itself fails (not the tests)
     */
    ValidationResult runTests(Path projectRoot, BuildTool buildTool, String testClassFqn, Path testFile);
}
