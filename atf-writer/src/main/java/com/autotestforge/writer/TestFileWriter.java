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
import java.util.Optional;

/**
 * {@link TestWriterPort} adapter: writes each generated test into the
 * {@code src/test/java} root of the module owning the class under test,
 * mirroring the package structure. Multi-module safe because the module is
 * derived from the source file location, not from the project root.
 */
public class TestFileWriter implements TestWriterPort {

    private static final Logger log = LoggerFactory.getLogger(TestFileWriter.class);

    private static final Path MAIN_SOURCE_SUFFIX = Path.of("src", "main", "java");
    private static final Path TEST_SOURCE_SUFFIX = Path.of("src", "test", "java");

    @Override
    public Path writeTest(JavaClassInfo classUnderTest, GeneratedTestFile test) {
        Path testSourceRoot = moduleRootOf(classUnderTest).resolve(TEST_SOURCE_SUFFIX);
        return write(testSourceRoot, test);
    }

    @Override
    public Path writeTestTo(Path outputRoot, Path projectRoot, JavaClassInfo classUnderTest, GeneratedTestFile test) {
        Path moduleRoot = moduleRootOf(classUnderTest).toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        Path relativeModule = moduleRoot.startsWith(root) ? root.relativize(moduleRoot) : Path.of("");
        Path testSourceRoot = outputRoot.resolve(relativeModule).resolve(TEST_SOURCE_SUFFIX);
        return write(testSourceRoot, test);
    }

    @Override
    public Optional<Path> locateExistingTest(JavaClassInfo classUnderTest, String testClassName) {
        Path candidate = moduleRootOf(classUnderTest)
                .resolve(TEST_SOURCE_SUFFIX)
                .resolve(packageDir(classUnderTest.packageName()))
                .resolve(testClassName + ".java");
        return Files.isRegularFile(candidate) ? Optional.of(candidate.toAbsolutePath()) : Optional.empty();
    }

    private Path write(Path testSourceRoot, GeneratedTestFile test) {
        Path packageDir = testSourceRoot.resolve(packageDir(test.packageName()));
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

    /** Walks up from the source file to its {@code src/main/java} root and returns the module directory. */
    private Path moduleRootOf(JavaClassInfo classUnderTest) {
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
        return moduleRoot == null ? Path.of("") : moduleRoot;
    }

    private Path packageDir(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return Path.of("");
        }
        return Path.of(packageName.replace('.', '/'));
    }
}
