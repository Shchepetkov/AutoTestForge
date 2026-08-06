package com.autotestforge.core.port.out;

import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.exception.TestWriteException;

import java.nio.file.Path;

/** Driven port: persists generated tests inside the target project. */
public interface TestWriterPort {

    /**
     * Writes {@code test} into the test source root of the module that owns
     * {@code classUnderTest}, mirroring its package structure.
     *
     * @return absolute path of the written file
     * @throws TestWriteException on I/O errors
     */
    Path writeTest(JavaClassInfo classUnderTest, GeneratedTestFile test);
}
