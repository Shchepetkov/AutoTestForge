package com.autotestforge.ai;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.ExternalContextSnippet;
import com.autotestforge.core.domain.ExternalTestContext;
import com.autotestforge.core.domain.FieldDependency;
import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.GenerationContext;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.ParameterInfo;
import com.autotestforge.core.domain.TestFailure;
import com.autotestforge.core.domain.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestPromptBuilderTest {

    private final TestPromptBuilder builder = new TestPromptBuilder();

    @Test
    @DisplayName("generation prompt contains source, API, mocks and naming constraints")
    void buildGenerationPrompt_shouldContainFullContext() {
        JavaClassInfo classInfo = serviceClass();

        String prompt = builder.buildGenerationPrompt(classInfo);

        assertThat(prompt)
                .contains("public class OrderService")             // full source
                .contains("String find(long id)")                  // method signature
                .contains("com.acme.OrderRepository repository (injected)")  // mock candidate
                .contains("`com.acme`")                            // package constraint
                .contains("`OrderServiceTest`")                    // naming constraint
                .contains("Arrange-Act-Assert")
                .contains("assertThatThrownBy");
    }

    @Test
    @DisplayName("controller classes get MockMvc guidance instead of @SpringBootTest")
    void buildGenerationPrompt_shouldSuggestMockMvc_whenClassIsController() {
        JavaClassInfo controller = new JavaClassInfo("com.acme", "OrderController", ClassKind.CLASS, false,
                "public class OrderController {}", "", List.of("RestController"),
                List.of(method()), List.of(), List.of(), Path.of("OrderController.java"), "RestController");

        String prompt = builder.buildGenerationPrompt(controller);

        assertThat(prompt).contains("MockMvcBuilders.standaloneSetup");
    }

    @Test
    @DisplayName("classes without collaborators explicitly forbid Mockito annotations")
    void buildGenerationPrompt_shouldForbidMocks_whenNoCollaborators() {
        JavaClassInfo plain = new JavaClassInfo("com.acme", "StringUtils", ClassKind.CLASS, false,
                "public class StringUtils {}", "", List.of(),
                List.of(method()), List.of(), List.of(), Path.of("StringUtils.java"), null);

        String prompt = builder.buildGenerationPrompt(plain);

        assertThat(prompt).contains("no collaborators, do not use Mockito annotations");
    }

    @Test
    @DisplayName("generation prompt includes external business and TMS context")
    void buildGenerationPrompt_shouldIncludeExternalContext() {
        ExternalTestContext context = new ExternalTestContext(List.of(
                new ExternalContextSnippet("confluence", "Order business rules",
                        "VIP customers receive expedited handling."),
                new ExternalContextSnippet("zephyr", "Regression test ATF-T42",
                        "Verify cancelled orders cannot be paid.")));

        String prompt = builder.buildGenerationPrompt(serviceClass(), context);

        assertThat(prompt)
                .contains("External business and test-management context")
                .contains("Source: confluence")
                .contains("VIP customers receive expedited handling")
                .contains("Source: zephyr")
                .contains("Verify cancelled orders cannot be paid")
                .contains("Zephyr/TMS XML exports")
                .contains("Business/TMS coverage");
    }

    @Test
    @DisplayName("related project types are embedded: small ones as full source, large ones as public API")
    void buildGenerationPrompt_shouldIncludeRelatedTypes() {
        JavaClassInfo order = new JavaClassInfo("com.acme", "Order", ClassKind.RECORD, false,
                "package com.acme;\n\npublic record Order(long id, String customerEmail) {}", "",
                List.of(), List.of(), List.of(), List.of(), Path.of("Order.java"), null);
        JavaClassInfo hugeRepository = new JavaClassInfo("com.acme", "OrderRepository", ClassKind.INTERFACE, false,
                "x".repeat(5_000), "Persists orders. Thread-safe.", List.of(),
                List.of(new MethodInfo("findById", "java.util.Optional<com.acme.Order>",
                        List.of(new ParameterInfo("id", "long")), List.of(), "", List.of(), false)),
                List.of(), List.of(), Path.of("OrderRepository.java"), "Repository");
        GenerationContext context = new GenerationContext(ExternalTestContext.empty(), List.of(order, hugeRepository));

        String prompt = builder.buildGenerationPrompt(serviceClass(), context);

        assertThat(prompt)
                .contains("## Related project types")
                .contains("### record com.acme.Order")
                .contains("public record Order(long id, String customerEmail) {}")
                .contains("### interface com.acme.OrderRepository (@Repository)")
                .contains("Javadoc: Persists orders.")
                .contains("- java.util.Optional<com.acme.Order> findById(long id)")
                .doesNotContain("xxxxxxxxxx");
        assertThat(builder.buildGenerationPrompt(serviceClass())).doesNotContain("## Related project types");
    }

    @Test
    @DisplayName("utility classes with only static methods are not instantiated or mocked")
    void buildGenerationPrompt_shouldHintStaticUsage_whenAllMethodsAreStatic() {
        JavaClassInfo utils = new JavaClassInfo("com.acme", "TextUtils", ClassKind.CLASS, false,
                "public final class TextUtils {}", "", List.of(),
                List.of(new MethodInfo("reverse", "String", List.of(new ParameterInfo("s", "String")),
                        List.of(), "", List.of(), true)),
                List.of(), List.of(), Path.of("TextUtils.java"), null);

        assertThat(builder.buildGenerationPrompt(utils)).contains("All public methods are static");
    }

    @Test
    @DisplayName("oversized sources are truncated with a marker instead of blowing the context window")
    void buildGenerationPrompt_shouldTruncateHugeSources() {
        JavaClassInfo huge = new JavaClassInfo("com.acme", "Huge", ClassKind.CLASS, false,
                "y".repeat(60_000), "", List.of(), List.of(method()), List.of(), List.of(), Path.of("Huge.java"), null);

        String prompt = builder.buildGenerationPrompt(huge);

        assertThat(prompt).contains("source truncated by AutoTestForge");
        assertThat(prompt.length()).isLessThan(50_000);
    }

    @Test
    @DisplayName("format retry prompt quotes the parse problem")
    void buildFormatRetryPrompt_shouldMentionReason() {
        assertThat(builder.buildFormatRetryPrompt("no code block"))
                .contains("no code block")
                .contains("EXACTLY ONE fenced");
        assertThat(builder.buildFormatRetryPrompt(null)).contains("no Java class was found");
    }

    @Test
    @DisplayName("fix prompt carries the failing test, structured failures and the runner log")
    void buildFixPrompt_shouldContainFailuresAndPreviousTest() {
        GeneratedTestFile previous = new GeneratedTestFile("com.acme", "OrderServiceTest",
                "class OrderServiceTest { void broken() {} }");
        ValidationResult failure = ValidationResult.failure(
                List.of(new TestFailure("com.acme.OrderServiceTest", "find_shouldReturnOrder",
                        "expected X but was Y", "stacktrace...")),
                "BUILD FAILURE: compilation error");

        String prompt = builder.buildFixPrompt(serviceClass(), previous, failure);

        assertThat(prompt)
                .contains("void broken()")
                .contains("find_shouldReturnOrder")
                .contains("expected X but was Y")
                .contains("BUILD FAILURE")
                .contains("`OrderServiceTest`");
    }

    private JavaClassInfo serviceClass() {
        return new JavaClassInfo("com.acme", "OrderService", ClassKind.CLASS, false,
                "public class OrderService { /* body */ }",
                "Handles orders.",
                List.of("Service"),
                List.of(method()),
                List.of(new FieldDependency("repository", "com.acme.OrderRepository", true)),
                List.of("com.acme.OrderRepository"),
                Path.of("OrderService.java"),
                "Service");
    }

    private MethodInfo method() {
        return new MethodInfo("find", "String",
                List.of(new ParameterInfo("id", "long")),
                List.of(), "Finds an order.", List.of(), false);
    }
}
