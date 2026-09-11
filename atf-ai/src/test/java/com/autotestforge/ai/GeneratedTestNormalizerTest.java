package com.autotestforge.ai;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.JavaClassInfo;
import com.github.javaparser.JavaParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedTestNormalizerTest {

    private final GeneratedTestNormalizer normalizer = new GeneratedTestNormalizer();
    private final JavaClassInfo orderService = new JavaClassInfo("com.acme.orders", "OrderService",
            ClassKind.CLASS, false, "public class OrderService {}", "", List.of(), List.of(), List.of(), List.of(),
            Path.of("OrderService.java"), null);

    @Test
    @DisplayName("a conforming test is returned untouched")
    void normalize_shouldKeepConformingTest() {
        GeneratedTestFile test = new GeneratedTestFile("com.acme.orders", "OrderServiceTest",
                "package com.acme.orders;\n\nclass OrderServiceTest {}\n");

        assertThat(normalizer.normalize(test, orderService)).isSameAs(test);
    }

    @Test
    @DisplayName("a wrong package declaration is rewritten in place")
    void normalize_shouldFixPackage() {
        GeneratedTestFile test = new GeneratedTestFile("com.acme", "OrderServiceTest", """
                // generated
                package com.acme;

                import org.junit.jupiter.api.Test;

                class OrderServiceTest {
                    @Test
                    void pay_shouldWork() {}
                }
                """);

        GeneratedTestFile normalized = normalizer.normalize(test, orderService);

        assertThat(normalized.packageName()).isEqualTo("com.acme.orders");
        assertThat(normalized.sourceCode())
                .startsWith("// generated\npackage com.acme.orders;\n\nimport org.junit.jupiter.api.Test;")
                .contains("class OrderServiceTest {");
        assertThat(new JavaParser().parse(normalized.sourceCode()).isSuccessful()).isTrue();
    }

    @Test
    @DisplayName("a missing package declaration is inserted before the imports")
    void normalize_shouldInsertPackage_whenMissing() {
        GeneratedTestFile test = new GeneratedTestFile("", "OrderServiceTest",
                "import org.junit.jupiter.api.Test;\n\nclass OrderServiceTest {}\n");

        GeneratedTestFile normalized = normalizer.normalize(test, orderService);

        assertThat(normalized.sourceCode())
                .startsWith("package com.acme.orders;\n\nimport org.junit.jupiter.api.Test;");
        assertThat(new JavaParser().parse(normalized.sourceCode()).isSuccessful()).isTrue();
    }

    @Test
    @DisplayName("an unconventional class name is renamed everywhere in the file")
    void normalize_shouldRenameClass() {
        GeneratedTestFile test = new GeneratedTestFile("com.acme.orders", "OrderServiceTests", """
                package com.acme.orders;

                class OrderServiceTests {
                    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OrderServiceTests.class);
                    private final OrderService service = new OrderService();
                }
                """);

        GeneratedTestFile normalized = normalizer.normalize(test, orderService);

        assertThat(normalized.className()).isEqualTo("OrderServiceTest");
        assertThat(normalized.fullyQualifiedName()).isEqualTo("com.acme.orders.OrderServiceTest");
        assertThat(normalized.sourceCode())
                .contains("class OrderServiceTest {")
                .contains("getLogger(OrderServiceTest.class)")
                .contains("new OrderService()")
                .doesNotContain("OrderServiceTests");
    }

    @Test
    @DisplayName("when the model reused the name of the class under test, only the declaration is renamed")
    void normalize_shouldRenameDeclarationOnly_whenNameClashesWithClassUnderTest() {
        GeneratedTestFile test = new GeneratedTestFile("com.acme.orders", "OrderService", """
                package com.acme.orders;

                class OrderService {
                    private final OrderService subject = new OrderService();
                }
                """);

        GeneratedTestFile normalized = normalizer.normalize(test, orderService);

        assertThat(normalized.className()).isEqualTo("OrderServiceTest");
        assertThat(normalized.sourceCode())
                .contains("class OrderServiceTest {")
                .contains("private final OrderService subject = new OrderService();");
    }

    @Test
    @DisplayName("default package classes under test get their package declaration removed")
    void normalize_shouldRemovePackage_whenClassUnderTestIsInDefaultPackage() {
        JavaClassInfo defaultPackage = new JavaClassInfo("", "Tool", ClassKind.CLASS, false, "", "",
                List.of(), List.of(), List.of(), List.of(), Path.of("Tool.java"), null);
        GeneratedTestFile test = new GeneratedTestFile("com.acme", "ToolTest",
                "package com.acme;\n\nclass ToolTest {}\n");

        GeneratedTestFile normalized = normalizer.normalize(test, defaultPackage);

        assertThat(normalized.packageName()).isEmpty();
        assertThat(normalized.sourceCode()).startsWith("class ToolTest {}");
    }
}
