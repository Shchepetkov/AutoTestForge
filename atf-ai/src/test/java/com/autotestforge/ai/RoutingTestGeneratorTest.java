package com.autotestforge.ai;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.GenerationContext;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.LlmException;
import com.autotestforge.core.port.out.AiTestGeneratorPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingTestGeneratorTest {

    private final JavaClassInfo classInfo = new JavaClassInfo("com.acme", "Thing", ClassKind.CLASS, false,
            "", "", List.of(), List.of(), List.of(), List.of(), Path.of("Thing.java"), null);

    private final AiTestGeneratorPort offline = stub("offline");
    private final AiTestGeneratorPort openAi = stub("openai");

    @Test
    @DisplayName("requests without an override go to the default provider; overrides are case-insensitive")
    void generate_shouldRouteByProvider() {
        RoutingTestGenerator router = new RoutingTestGenerator(Map.of("offline", offline, "OpenAI", openAi), "offline");

        assertThat(router.generate(classInfo, null, GenerationContext.empty()).sourceCode()).isEqualTo("offline");
        assertThat(router.generate(classInfo, "  ", GenerationContext.empty()).sourceCode()).isEqualTo("offline");
        assertThat(router.generate(classInfo, "OPENAI", GenerationContext.empty()).sourceCode()).isEqualTo("openai");
        assertThat(router.fix(classInfo, null, null, "openai", GenerationContext.empty()).sourceCode())
                .isEqualTo("openai");
        assertThat(router.providers()).containsExactlyInAnyOrder("offline", "openai");
        assertThat(router.defaultProvider()).isEqualTo("offline");
    }

    @Test
    @DisplayName("unknown providers fail with a helpful message listing the configured ones")
    void generate_shouldThrow_whenProviderUnknown() {
        RoutingTestGenerator router = new RoutingTestGenerator(Map.of("offline", offline), "offline");

        assertThatThrownBy(() -> router.generate(classInfo, "anthropic", GenerationContext.empty()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("anthropic")
                .hasMessageContaining("[offline]");
    }

    @Test
    @DisplayName("a default provider that is not configured is rejected at construction time")
    void constructor_shouldRejectUnknownDefault() {
        assertThatThrownBy(() -> new RoutingTestGenerator(Map.of("offline", offline), "openai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openai");
    }

    private static AiTestGeneratorPort stub(String marker) {
        return new AiTestGeneratorPort() {
            @Override
            public GeneratedTestFile generate(JavaClassInfo classInfo, String provider, GenerationContext context) {
                return new GeneratedTestFile("com.acme", "ThingTest", marker);
            }

            @Override
            public GeneratedTestFile fix(JavaClassInfo classInfo, GeneratedTestFile previousTest,
                                         ValidationResult validationResult, String provider,
                                         GenerationContext context) {
                return new GeneratedTestFile("com.acme", "ThingTest", marker);
            }
        };
    }
}
