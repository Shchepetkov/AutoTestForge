package com.autotestforge.spring;

import com.autotestforge.ai.RoutingTestGenerator;
import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.core.port.in.ScanProjectUseCase;
import com.autotestforge.core.port.out.ExternalContextPort;
import com.autotestforge.core.port.out.TestValidatorPort;
import com.autotestforge.mcp.McpExternalContextProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AtfAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtfAutoConfiguration.class));

    @Test
    @DisplayName("defaults alone wire the whole hexagon with offline and ollama providers")
    void autoConfiguration_shouldWireEverythingFromDefaults() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GenerateTestsUseCase.class);
            assertThat(context).hasSingleBean(ScanProjectUseCase.class);
            assertThat(context).hasSingleBean(TestValidatorPort.class);
            assertThat(context).hasSingleBean(ExternalContextPort.class);

            AtfProperties properties = context.getBean(AtfProperties.class);
            assertThat(properties.llm().provider()).isEqualTo("ollama");
            assertThat(properties.llm().ollama().timeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.generation().parallelism()).isEqualTo(1);
            assertThat(properties.generation().overwriteExisting()).isFalse();
            assertThat(properties.validation().maxFixAttempts()).isEqualTo(2);
            assertThat(properties.context().enabled()).isFalse();

            RoutingTestGenerator router = context.getBean(RoutingTestGenerator.class);
            assertThat(router.providers()).containsExactlyInAnyOrder("offline", "ollama");
            assertThat(context.getBean(McpExternalContextProvider.class).configuredSources()).isEmpty();
        });
    }

    @Test
    @DisplayName("OpenAI-compatible and Anthropic providers are registered when configured")
    void autoConfiguration_shouldRegisterOptionalProviders() {
        runner.withPropertyValues(
                        "atf.llm.provider=openai",
                        "atf.llm.openai.base-url=http://localhost:1234/v1",
                        "atf.llm.openai.model=local-model",
                        "atf.llm.anthropic.api-key=sk-ant-test")
                .run(context -> {
                    RoutingTestGenerator router = context.getBean(RoutingTestGenerator.class);
                    assertThat(router.providers()).containsExactlyInAnyOrder("offline", "ollama", "openai", "anthropic");
                    assertThat(router.defaultProvider()).isEqualTo("openai");
                });
    }

    @Test
    @DisplayName("static MCP sources are bound and honoured only when context is enabled")
    void autoConfiguration_shouldBindContextSources() {
        runner.withPropertyValues(
                        "atf.context.enabled=true",
                        "atf.context.sources[0].name=confluence",
                        "atf.context.sources[0].enabled=true",
                        "atf.context.sources[0].command=npx",
                        "atf.context.sources[0].args[0]=-y",
                        "atf.context.sources[0].args[1]=@acme/confluence-mcp",
                        "atf.context.sources[0].tool-name=search",
                        "atf.context.sources[0].timeout=45s",
                        "atf.context.sources[0].max-chars=5000")
                .run(context -> {
                    McpExternalContextProvider provider = context.getBean(McpExternalContextProvider.class);
                    assertThat(provider.configuredSources()).hasSize(1);
                    assertThat(provider.configuredSources().get(0).name()).isEqualTo("confluence");
                    assertThat(provider.configuredSources().get(0).commandLine())
                            .containsExactly("npx", "-y", "@acme/confluence-mcp");
                    assertThat(provider.configuredSources().get(0).timeout()).isEqualTo(Duration.ofSeconds(45));
                });

        runner.withPropertyValues(
                        "atf.context.enabled=false",
                        "atf.context.sources[0].name=confluence",
                        "atf.context.sources[0].enabled=true",
                        "atf.context.sources[0].command=npx",
                        "atf.context.sources[0].tool-name=search")
                .run(context -> assertThat(context.getBean(McpExternalContextProvider.class).configuredSources())
                        .isEmpty());
    }

    @Test
    @DisplayName("an unknown default provider fails fast at startup")
    void autoConfiguration_shouldFail_whenDefaultProviderUnknown() {
        runner.withPropertyValues("atf.llm.provider=gemini")
                .run(context -> assertThat(context).hasFailed());
    }
}
