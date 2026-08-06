package com.autotestforge.scanner;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.ScannedProject;
import com.autotestforge.core.exception.ScanException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaParserProjectScannerTest {

    @TempDir
    Path projectRoot;

    private final JavaParserProjectScanner scanner = new JavaParserProjectScanner();

    private Path sourceRoot;

    @BeforeEach
    void createSourceRoot() throws IOException {
        sourceRoot = projectRoot.resolve(Path.of("src", "main", "java", "com", "acme"));
        Files.createDirectories(sourceRoot);
    }

    @Test
    @DisplayName("scanner extracts package, methods, javadoc, dependencies and Spring stereotype")
    void scan_shouldExtractFullClassMetadata() throws IOException {
        writeSource("OrderRepository.java", """
                package com.acme;

                public interface OrderRepository {
                    String findOrder(long id);
                }
                """);
        writeSource("OrderService.java", """
                package com.acme;

                import org.springframework.stereotype.Service;

                /** Handles order lookups. */
                @Service
                public class OrderService {

                    private final OrderRepository repository;

                    public OrderService(OrderRepository repository) {
                        this.repository = repository;
                    }

                    /**
                     * Finds an order by id.
                     * @throws IllegalArgumentException when id is negative
                     */
                    public String find(long id) {
                        if (id < 0) {
                            throw new IllegalArgumentException("negative id");
                        }
                        return repository.findOrder(id);
                    }

                    private void internalHelper() {
                    }
                }
                """);

        ScannedProject project = scanner.scan(projectRoot);

        assertThat(project.classes()).hasSize(2);
        JavaClassInfo service = classNamed(project, "OrderService");
        assertThat(service.packageName()).isEqualTo("com.acme");
        assertThat(service.kind()).isEqualTo(ClassKind.CLASS);
        assertThat(service.javadoc()).contains("Handles order lookups");
        assertThat(service.springStereotype()).isEqualTo("Service");
        assertThat(service.publicMethods())
                .extracting(MethodInfo::name)
                .containsExactly("find")
                .doesNotContain("internalHelper");
        assertThat(service.publicMethods().get(0).javadoc()).contains("Finds an order by id");
        assertThat(service.dependencies())
                .anySatisfy(dependency -> {
                    assertThat(dependency.fieldName()).isEqualTo("repository");
                    assertThat(dependency.injected()).isTrue();
                });
    }

    @Test
    @DisplayName("dependency graph links classes across the project")
    void scan_shouldBuildDependencyGraph() throws IOException {
        writeSource("OrderRepository.java", """
                package com.acme;

                public interface OrderRepository {
                    String findOrder(long id);
                }
                """);
        writeSource("OrderService.java", """
                package com.acme;

                public class OrderService {
                    private final OrderRepository repository;

                    public OrderService(OrderRepository repository) {
                        this.repository = repository;
                    }

                    public String find(long id) {
                        return repository.findOrder(id);
                    }
                }
                """);

        ScannedProject project = scanner.scan(projectRoot);

        assertThat(project.dependencyGraph().dependenciesOf("com.acme.OrderService"))
                .containsExactly("com.acme.OrderRepository");
        assertThat(project.dependencyGraph().dependentsOf("com.acme.OrderRepository"))
                .containsExactly("com.acme.OrderService");
    }

    @Test
    @DisplayName("records and enums are recognized with their kinds")
    void scan_shouldRecognizeRecordsAndEnums() throws IOException {
        writeSource("Money.java", """
                package com.acme;

                public record Money(String currency, long amount) {
                    public Money add(Money other) {
                        return new Money(currency, amount + other.amount());
                    }
                }
                """);
        writeSource("Status.java", """
                package com.acme;

                public enum Status {
                    NEW, PAID;

                    public boolean isFinal() {
                        return this == PAID;
                    }
                }
                """);

        ScannedProject project = scanner.scan(projectRoot);

        assertThat(classNamed(project, "Money").kind()).isEqualTo(ClassKind.RECORD);
        assertThat(classNamed(project, "Status").kind()).isEqualTo(ClassKind.ENUM);
    }

    @Test
    @DisplayName("a broken source file is skipped, the rest of the project is still scanned")
    void scan_shouldSkipUnparsableFiles() throws IOException {
        writeSource("Broken.java", "this is not java at all {{{");
        writeSource("Fine.java", """
                package com.acme;

                public class Fine {
                    public int answer() {
                        return 42;
                    }
                }
                """);

        ScannedProject project = scanner.scan(projectRoot);

        assertThat(project.classes())
                .extracting(JavaClassInfo::className)
                .containsExactly("Fine");
    }

    @Test
    @DisplayName("scanning a directory without sources fails fast with a clear error")
    void scan_shouldThrow_whenNoSourceRoots() {
        assertThatThrownBy(() -> scanner.scan(projectRoot.resolve("does-not-exist")))
                .isInstanceOf(ScanException.class)
                .hasMessageContaining("does not exist");

        Path emptyProject = projectRoot.resolve("empty");
        assertThatThrownBy(() -> {
            Files.createDirectories(emptyProject);
            scanner.scan(emptyProject);
        }).isInstanceOf(ScanException.class)
                .hasMessageContaining("No src/main/java");
    }

    private void writeSource(String fileName, String content) throws IOException {
        Files.writeString(sourceRoot.resolve(fileName), content);
    }

    private JavaClassInfo classNamed(ScannedProject project, String simpleName) {
        return project.classes().stream()
                .filter(c -> c.className().equals(simpleName))
                .findFirst()
                .orElseThrow();
    }
}
