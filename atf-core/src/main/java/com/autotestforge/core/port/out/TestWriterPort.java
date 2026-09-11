package com.autotestforge.core.port.out;

import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.exception.TestWriteException;

import java.nio.file.Path;
import java.util.Optional;

/** Driven port: persists generated tests inside (or next to) the target project. */
public interface TestWriterPort {

    /**
     * Writes {@code test} into the test source root of the module that owns
     * {@code classUnderTest}, mirroring its package structure.
     *
     * @return absolute path of the written file
     * @throws TestWriteException on I/O errors
     */
    Path writeTest(JavaClassInfo classUnderTest, GeneratedTestFile test);

    /**
     * Writes {@code test} under {@code outputRoot} instead of the project,
     * mirroring the module layout ({@code <module>/src/test/java/<package>/}) so
     * the directory can later be copied over the project as-is.
     *
     * @return absolute path of the written file
     */
    Path writeTestTo(Path outputRoot, Path projectRoot, JavaClassInfo classUnderTest, GeneratedTestFile test);

    /**
     * Locates an already existing test for {@code classUnderTest} following the
     * conventional location ({@code src/test/java/<package>/<testClassName>.java}).
     */
    Optional<Path> locateExistingTest(JavaClassInfo classUnderTest, String testClassName);
}
