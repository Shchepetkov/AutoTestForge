package com.autotestforge.writer;

import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.exception.TestWriteException;
import com.autotestforge.core.port.out.TestWriterPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import javax.lang.model.SourceVersion;

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
        validateIdentifier(test.className(), "test class");
        Path testSourceRoot = testSourceRootFor(classUnderTest);
        Path packageDir = resolvePackageDir(testSourceRoot, test.packageName());
        Path testFile = packageDir.resolve(test.className() + ".java").normalize();
        if (!testFile.startsWith(testSourceRoot)) {
            throw new TestWriteException("Test destination escapes source root: " + testFile);
        }
        try {
            rejectSymbolicLinks(testFile);
            Files.createDirectories(packageDir);
            rejectSymbolicLinks(testFile);
            boolean existed = Files.exists(testFile);
            // Replace the directory entry, rather than truncating a possibly hard-linked file.
            Path temporaryFile = packageDir.resolve(".atf-" + UUID.randomUUID() + ".tmp");
            boolean temporaryCreated = false;
            try {
                try (BufferedWriter output = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    temporaryCreated = true;
                    output.write(test.sourceCode());
                }
                rejectSymbolicLinks(testFile);
                try {
                    Files.move(temporaryFile, testFile,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
                    Files.move(temporaryFile, testFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException failure) {
                if (temporaryCreated) {
                    try {
                        Files.deleteIfExists(temporaryFile);
                    } catch (IOException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
            log.info("{} test file {}", existed ? "Overwrote" : "Created", testFile);
            return testFile.toAbsolutePath();
        } catch (IOException e) {
            throw new TestWriteException("Failed to write test file " + testFile, e);
        }
    }

    /** Walks up from the source file to its {@code src/main/java} root and mirrors it as {@code src/test/java}. */
    private Path testSourceRootFor(JavaClassInfo classUnderTest) {
        Path current = classUnderTest.sourceFile().toAbsolutePath().normalize();
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
        Path packageDir = testSourceRoot;
        for (String segment : packageName.split("\\.", -1)) {
            validateIdentifier(segment, "package segment");
            packageDir = packageDir.resolve(segment);
        }
        return packageDir;
    }

    private void validateIdentifier(String name, String role) {
        if (name == null || !SourceVersion.isIdentifier(name)
                || name.codePoints().anyMatch(Character::isIdentifierIgnorable)
                || SourceVersion.isKeyword(name, SourceVersion.RELEASE_17)) {
            throw new TestWriteException("Invalid " + role + " name: " + name);
        }
    }

    private void rejectSymbolicLinks(Path destination) throws IOException {
        Path current = destination.getRoot();
        for (Path segment : destination) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new TestWriteException("Refusing to write through symbolic link: " + current);
            }
            // Also detect directory junctions/reparse points on Windows.
            if (Files.exists(current) && !current.toRealPath().equals(current)) {
                throw new TestWriteException("Refusing redirected test destination: " + current);
            }
        }
    }
}
