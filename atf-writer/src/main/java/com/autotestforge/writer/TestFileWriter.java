package com.autotestforge.writer;

import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.exception.TestWriteException;
import com.autotestforge.core.port.out.TestWriterPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link TestWriterPort} adapter: writes each generated test into the
 * {@code src/test/java} root of the module owning the class under test,
 * mirroring the package structure. Multi-module safe because the module is
 * derived from the source file location, not from the project root.
 */
public class TestFileWriter implements TestWriterPort {

    private static final Logger log = LoggerFactory.getLogger(TestFileWriter.class);

    private static final Path MAIN_SOURCE_SUFFIX = Path.of("src", "main", "java");

    @Override
    public Path writeTest(JavaClassInfo classUnderTest, GeneratedTestFile test) {
        Path testSourceRoot = testSourceRootFor(classUnderTest);
        Path packageDir = resolvePackageDir(testSourceRoot, test.packageName());
        Path testFile = packageDir.resolve(test.className() + ".java");
        try {
            Files.createDirectories(packageDir);
            boolean existed = Files.exists(testFile);
            Files.writeString(testFile, test.sourceCode());
            log.info("{} test file {}", existed ? "Overwrote" : "Created", testFile);
            return testFile.toAbsolutePath();
        } catch (IOException e) {
            throw new TestWriteException("Failed to write test file " + testFile, e);
        }
    }

    /** Walks up from the source file to its {@code src/main/java} root and mirrors it as {@code src/test/java}. */
    private Path testSourceRootFor(JavaClassInfo classUnderTest) {
        Path current = classUnderTest.sourceFile();
        while (current != null && !current.endsWith(MAIN_SOURCE_SUFFIX)) {
            current = current.getParent();
        }
        if (current == null) {
            throw new TestWriteException("Cannot locate src/main/java above source file "
                    + classUnderTest.sourceFile());
        }
        // current = <module>/src/main/java -> strip the three trailing segments
        Path moduleRoot = current.getParent().getParent().getParent();
        return moduleRoot.resolve(Path.of("src", "test", "java"));
    }

    private Path resolvePackageDir(Path testSourceRoot, String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return testSourceRoot;
        }
        return testSourceRoot.resolve(packageName.replace('.', '/'));
    }
}
