package com.autotestforge.writer;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.exception.TestWriteException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFileWriterTest {

    @TempDir
    Path projectRoot;

    private final TestFileWriter writer = new TestFileWriter();

    @Test
    @DisplayName("test file mirrors the package structure under src/test/java of the owning module")
    void writeTest_shouldMirrorPackageStructure() throws IOException {
        Path sourceFile = projectRoot.resolve(
                Path.of("module-a", "src", "main", "java", "com", "acme", "OrderService.java"));
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "public class OrderService {}");

        Path written = writer.writeTest(
                classAt(sourceFile),
                new GeneratedTestFile("com.acme", "OrderServiceTest", "class OrderServiceTest {}"));

        assertThat(written).isEqualTo(projectRoot
                .resolve(Path.of("module-a", "src", "test", "java", "com", "acme", "OrderServiceTest.java"))
                .toAbsolutePath());
        assertThat(Files.readString(written)).isEqualTo("class OrderServiceTest {}");
    }

    @Test
    @DisplayName("existing test files are overwritten with the newly generated content")
    void writeTest_shouldOverwriteExistingFile() throws IOException {
        Path sourceFile = projectRoot.resolve(
                Path.of("src", "main", "java", "com", "acme", "Thing.java"));
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "public class Thing {}");
        JavaClassInfo classInfo = classAt(sourceFile);

        writer.writeTest(classInfo, new GeneratedTestFile("com.acme", "ThingTest", "old"));
        Path written = writer.writeTest(classInfo, new GeneratedTestFile("com.acme", "ThingTest", "new"));

        assertThat(Files.readString(written)).isEqualTo("new");
    }

    @Test
    @DisplayName("a source file outside src/main/java is rejected with a clear error")
    void writeTest_shouldThrow_whenSourceFileIsNotUnderMainSourceRoot() {
        Path orphan = projectRoot.resolve("Strange.java");

        assertThatThrownBy(() -> writer.writeTest(
                classAt(orphan),
                new GeneratedTestFile("com.acme", "StrangeTest", "class StrangeTest {}")))
                .isInstanceOf(TestWriteException.class)
                .hasMessageContaining("Cannot locate src/main/java");
    }

    private JavaClassInfo classAt(Path sourceFile) {
        return new JavaClassInfo("com.acme", "AnyClass", ClassKind.CLASS, false, "", "",
                List.of(), List.of(), List.of(), List.of(), sourceFile, null);
    }
}
