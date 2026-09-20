package com.autotestforge.writer;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.exception.TestWriteException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestFileWriterPathSafetyTest {

    @TempDir
    Path projectRoot;

    private final TestFileWriter writer = new TestFileWriter();

    @Test
    @DisplayName("valid Java 17 identifiers support Unicode, dollar signs, underscores, and nested modules")
    void writeTest_shouldAcceptValidIdentifiersInNestedModule() throws IOException {
        Path sourceFile = sourceFile("services/billing", "Source.java");

        Path written = writer.writeTest(classAt(sourceFile),
                new GeneratedTestFile("тест.$pkg._внутри", "Тест$_", "class Тест$_ {}"));

        assertThat(written).isEqualTo(projectRoot.resolve(Path.of(
                "services", "billing", "src", "test", "java", "тест", "$pkg", "_внутри", "Тест$_.java"))
                .toAbsolutePath());
        assertThat(Files.readString(written)).isEqualTo("class Тест$_ {}");
    }

    @Test
    @DisplayName("null and blank packages write to the default package")
    void writeTest_shouldAcceptNullAndBlankPackage() throws IOException {
        Path sourceFile = sourceFile("module", "Source.java");

        Path nullPackageFile = writer.writeTest(classAt(sourceFile),
                new GeneratedTestFile(null, "NullPackageTest", "null-package"));
        Path blankPackageFile = writer.writeTest(classAt(sourceFile),
                new GeneratedTestFile("   ", "BlankPackageTest", "blank-package"));

        Path testRoot = projectRoot.resolve(Path.of("module", "src", "test", "java")).toAbsolutePath();
        assertThat(nullPackageFile).isEqualTo(testRoot.resolve("NullPackageTest.java"));
        assertThat(blankPackageFile).isEqualTo(testRoot.resolve("BlankPackageTest.java"));
    }

    @Test
    @DisplayName("invalid package names are rejected before any filesystem write")
    void writeTest_shouldRejectInvalidPackageNamesBeforeWriting() throws IOException {
        String[] invalidPackages = {
                "com/acme", "com\\acme", "..", ".", "com..acme", "com.", ".com",
                "../victim", "/absolute", "C:\\absolute", "class",
                "Bad" + Character.toString(0) + "Name"
        };

        int scenario = 0;
        for (String invalidPackage : invalidPackages) {
            Path sourceFile = sourceFile("package-" + scenario++, "Source.java");
            Path testRoot = testRootFor(sourceFile);

            assertThatThrownBy(() -> writer.writeTest(classAt(sourceFile),
                    new GeneratedTestFile(invalidPackage, "GeneratedTest", "generated")))
                    .as("package name: %s", invalidPackage)
                    .isInstanceOf(TestWriteException.class);
            assertThat(Files.exists(testRoot)).as("test root for package: %s", invalidPackage).isFalse();
        }
    }

    @Test
    @DisplayName("invalid class names are rejected before any filesystem write")
    void writeTest_shouldRejectInvalidClassNamesBeforeWriting() throws IOException {
        String[] invalidClasses = {null, "", " ", "class", "enum", "true", "_", "a/b", "a\\b", ".", "..", "/absolute", "C:\\absolute",
                "Bad" + Character.toString(0) + "Name"};

        int scenario = 0;
        for (String invalidClass : invalidClasses) {
            Path sourceFile = sourceFile("class-" + scenario++, "Source.java");
            Path testRoot = testRootFor(sourceFile);

            assertThatThrownBy(() -> writer.writeTest(classAt(sourceFile),
                    new GeneratedTestFile("com.acme", invalidClass, "generated")))
                    .as("class name: %s", invalidClass)
                    .isInstanceOf(TestWriteException.class);
            assertThat(Files.exists(testRoot)).as("test root for class: %s", invalidClass).isFalse();
        }
    }

    @Test
    @DisplayName("existing regular files remain overwritable")
    void writeTest_shouldOverwriteExistingRegularFile() throws IOException {
        Path sourceFile = sourceFile("module", "Source.java");
        JavaClassInfo classInfo = classAt(sourceFile);

        Path written = writer.writeTest(classInfo, new GeneratedTestFile("com.acme", "GeneratedTest", "old"));
        writer.writeTest(classInfo, new GeneratedTestFile("com.acme", "GeneratedTest", "new"));

        assertThat(Files.isRegularFile(written)).isTrue();
        assertThat(Files.readString(written)).isEqualTo("new");
    }

    @Test
    @DisplayName("a preexisting symlink at the output root is rejected and the victim is unchanged")
    void writeTest_shouldRejectSymlinkOutputRoot() throws IOException {
        Path sourceFile = sourceFile("module", "Source.java");
        Path outputRoot = projectRoot.resolve(Path.of("module", "src", "test", "java"));
        Path victim = projectRoot.resolve("root-victim");
        Path victimFile = victim.resolve(Path.of("com", "acme", "GeneratedTest.java"));
        Files.createDirectories(victimFile.getParent());
        Files.writeString(victimFile, "victim");
        Files.createDirectories(outputRoot.getParent());
        createSymlinkOrSkip(outputRoot, victim);

        assertThatThrownBy(() -> writer.writeTest(classAt(sourceFile), generatedFile()))
                .isInstanceOf(TestWriteException.class);
        assertThat(Files.readString(victimFile)).isEqualTo("victim");
    }

    @Test
    @DisplayName("a preexisting symlink in the package path is rejected and the victim is unchanged")
    void writeTest_shouldRejectSymlinkIntermediatePackageDirectory() throws IOException {
        Path sourceFile = sourceFile("module", "Source.java");
        Path outputRoot = projectRoot.resolve(Path.of("module", "src", "test", "java"));
        Path victim = projectRoot.resolve("intermediate-victim");
        Files.createDirectories(victim);
        Files.createDirectories(outputRoot);
        createSymlinkOrSkip(outputRoot.resolve("com"), victim);

        assertThatThrownBy(() -> writer.writeTest(classAt(sourceFile), generatedFile()))
                .isInstanceOf(TestWriteException.class);
        try (var entries = Files.list(victim)) {
            assertThat(entries.findAny()).isEmpty();
        }
    }

    @Test
    @DisplayName("a preexisting dangling final symlink is rejected")
    void writeTest_shouldRejectDanglingFinalSymlink() throws IOException {
        Path sourceFile = sourceFile("module", "Source.java");
        Path outputRoot = projectRoot.resolve(Path.of("module", "src", "test", "java"));
        Path packageDir = outputRoot.resolve(Path.of("com", "acme"));
        Path danglingTarget = projectRoot.resolve("missing-victim.java");
        Files.createDirectories(packageDir);
        Path finalFile = packageDir.resolve("GeneratedTest.java");
        createSymlinkOrSkip(finalFile, danglingTarget);

        assertThatThrownBy(() -> writer.writeTest(classAt(sourceFile), generatedFile()))
                .isInstanceOf(TestWriteException.class);
        assertThat(Files.isSymbolicLink(finalFile)).isTrue();
        assertThat(Files.exists(danglingTarget)).isFalse();
    }

    @Test
    @DisplayName("a preexisting live final symlink is rejected and the victim is unchanged")
    void writeTest_shouldRejectLiveFinalSymlink() throws IOException {
        Path sourceFile = sourceFile("module", "Source.java");
        Path outputRoot = testRootFor(sourceFile);
        Path packageDir = outputRoot.resolve(Path.of("com", "acme"));
        Path victim = projectRoot.resolve("live-victim.java");
        Files.createDirectories(packageDir);
        Files.writeString(victim, "victim");
        Path finalFile = packageDir.resolve("GeneratedTest.java");
        createSymlinkOrSkip(finalFile, victim);

        assertThatThrownBy(() -> writer.writeTest(classAt(sourceFile), generatedFile()))
                .isInstanceOf(TestWriteException.class);
        assertThat(Files.readString(victim)).isEqualTo("victim");
        assertThat(Files.isSymbolicLink(finalFile)).isTrue();
    }

    @Test
    @DisplayName("overwriting a hard-linked file leaves the other link unchanged")
    void writeTest_shouldNotModifyHardLinkVictim() throws IOException {
        Path sourceFile = sourceFile("module", "Source.java");
        Path destination = testRootFor(sourceFile).resolve(Path.of("com", "acme", "GeneratedTest.java"));
        Path victim = projectRoot.resolve("hard-link-victim.java");
        Files.createDirectories(destination.getParent());
        Files.writeString(victim, "victim");
        createHardLinkOrSkip(destination, victim);

        Path written = writer.writeTest(classAt(sourceFile), generatedFile());

        assertThat(Files.readString(victim)).isEqualTo("victim");
        assertThat(Files.readString(written)).isEqualTo("generated");
    }

    @Test
    @DisplayName("a failed replacement cleans its temporary file when the destination is a nonempty directory")
    void writeTest_shouldCleanTemporaryFileAfterFailedReplacement() throws IOException {
        Path sourceFile = sourceFile("module", "Source.java");
        Path destination = testRootFor(sourceFile).resolve(Path.of("com", "acme", "GeneratedTest.java"));
        Path child = destination.resolve("human-file.txt");
        Files.createDirectories(destination);
        Files.writeString(child, "human content");

        assertThatThrownBy(() -> writer.writeTest(classAt(sourceFile), generatedFile()))
                .isInstanceOf(TestWriteException.class);

        try (var entries = Files.list(destination)) {
            assertThat(entries.map(Path::getFileName).map(Path::toString).toList())
                    .containsExactly("human-file.txt");
        }
        assertThat(Files.readString(child)).isEqualTo("human content");
        try (var entries = Files.list(destination.getParent())) {
            assertThat(entries.map(Path::getFileName).map(Path::toString).toList())
                    .containsExactly("GeneratedTest.java");
        }
    }

    private GeneratedTestFile generatedFile() {
        return new GeneratedTestFile("com.acme", "GeneratedTest", "generated");
    }

    private Path sourceFile(String module, String fileName) throws IOException {
        Path sourceFile = projectRoot.resolve(Path.of(module, "src", "main", "java", "com", "acme", fileName));
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "class Source {}");
        return sourceFile;
    }

    private Path testRootFor(Path sourceFile) {
        return sourceFile.getParent().getParent().getParent().getParent().getParent().getParent()
                .resolve(Path.of("src", "test", "java"));
    }

    private JavaClassInfo classAt(Path sourceFile) {
        return new JavaClassInfo("com.acme", "Source", ClassKind.CLASS, false, "", "",
                List.of(), List.of(), List.of(), List.of(), sourceFile, null);
    }

    private void createSymlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symbolic links are unavailable: " + e.getClass().getSimpleName());
        } catch (FileSystemException e) {
            skipWhenLinkCapabilityUnavailable(e, "symbolic links");
            throw e;
        }
    }

    private void createHardLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createLink(link, target);
        } catch (UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "hard links are unavailable: " + e.getClass().getSimpleName());
        } catch (FileSystemException e) {
            skipWhenLinkCapabilityUnavailable(e, "hard links");
            throw e;
        }
    }

    private void skipWhenLinkCapabilityUnavailable(FileSystemException exception, String linkType) {
        String details = (String.valueOf(exception.getReason()) + " " + exception.getMessage())
                .toLowerCase(Locale.ROOT);
        boolean unavailable = details.contains("not supported")
                || details.contains("operation not supported")
                || details.contains("privilege")
                || details.contains("access is denied");
        if (unavailable) {
            assumeTrue(false, linkType + " unavailable: " + exception.getMessage());
        }
    }
}
