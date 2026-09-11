package com.autotestforge.mcp;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.ExternalContextRequest;
import com.autotestforge.core.domain.ExternalContextSourceRequest;
import com.autotestforge.core.domain.ExternalTestContext;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.TestGenerationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpExternalContextProviderTest {

    private static final Path PROJECT = Path.of("/repo");

    private final JavaClassInfo orderService = new JavaClassInfo("com.acme", "OrderService", ClassKind.CLASS, false,
            "public class OrderService {}", "", List.of(),
            List.of(new MethodInfo("pay", "void", List.of(), List.of(), "", List.of(), false)),
            List.of(), List.of(), PROJECT.resolve("OrderService.java"), null);

    @Test
    @DisplayName("context is disabled: no MCP process is started and nothing is returned")
    void fetchContext_shouldReturnEmpty_whenDisabled() {
        try (McpExternalContextProvider provider = new McpExternalContextProvider(List.of(source("confluence")))) {
            ExternalTestContext context = provider.fetchContext(orderService,
                    TestGenerationRequest.builder(PROJECT).build());

            assertThat(context.isEmpty()).isTrue();
        }
    }

    @Test
    @DisplayName("configured sources are queried with the rendered template and filtered by name")
    void fetchContext_shouldQuerySelectedSources() {
        try (McpExternalContextProvider provider = new McpExternalContextProvider(
                List.of(source("confluence"), source("zephyr")))) {
            TestGenerationRequest request = TestGenerationRequest.builder(PROJECT)
                    .externalContext(new ExternalContextRequest(true, "Rules for ${className}", List.of("zephyr")))
                    .build();

            ExternalTestContext context = provider.fetchContext(orderService, request);

            assertThat(context.snippets()).hasSize(1);
            assertThat(context.snippets().get(0).source()).isEqualTo("zephyr");
            assertThat(context.snippets().get(0).content()).isEqualTo("echo: Rules for OrderService");
        }
    }

    @Test
    @DisplayName("per-run sources from a driving adapter are used and released when the run finishes")
    void fetchContext_shouldUsePerRunSources_andReleaseThemAfterRun() {
        try (McpExternalContextProvider provider = new McpExternalContextProvider(List.of())) {
            ExternalContextSourceRequest perRun = new ExternalContextSourceRequest("jira", true,
                    McpStdioClientTest.serverCommand().get(0), McpStdioClientTest.serverCommand().subList(1, 4),
                    "echo", "query", "Find ${fullyQualifiedName}", Map.of(), Duration.ofSeconds(10), 8_000);
            TestGenerationRequest request = TestGenerationRequest.builder(PROJECT)
                    .externalContext(new ExternalContextRequest(true, null, List.of(), List.of(perRun), List.of()))
                    .build();

            ExternalTestContext first = provider.fetchContext(orderService, request);
            ExternalTestContext second = provider.fetchContext(orderService, request);
            provider.onRunFinished(request);

            assertThat(first.snippets().get(0).content()).isEqualTo("echo: Find com.acme.OrderService");
            assertThat(second.snippets().get(0).content()).isEqualTo("echo: Find com.acme.OrderService");
        }
    }

    @Test
    @DisplayName("a failing tool is skipped and never aborts generation")
    void fetchContext_shouldSkipFailingSource() {
        McpContextSource failing = new McpContextSource("broken", true,
                McpStdioClientTest.serverCommand().get(0), McpStdioClientTest.serverCommand().subList(1, 4),
                "fail", "query", null, Map.of(), Duration.ofSeconds(10), 8_000);
        try (McpExternalContextProvider provider = new McpExternalContextProvider(List.of(failing, source("ok")))) {
            TestGenerationRequest request = TestGenerationRequest.builder(PROJECT)
                    .externalContext(new ExternalContextRequest(true, null, List.of()))
                    .build();

            ExternalTestContext context = provider.fetchContext(orderService, request);

            assertThat(context.snippets()).extracting(s -> s.source()).containsExactly("ok");
        }
    }

    @Test
    @DisplayName("an unreachable MCP command is skipped with a warning")
    void fetchContext_shouldSkipUnstartableSource() {
        McpContextSource unstartable = new McpContextSource("ghost", true, "definitely-not-a-real-binary-atf",
                List.of(), "search", "query", null, Map.of(), Duration.ofSeconds(2), 8_000);
        try (McpExternalContextProvider provider = new McpExternalContextProvider(List.of(unstartable))) {
            ExternalTestContext context = provider.fetchContext(orderService, TestGenerationRequest.builder(PROJECT)
                    .externalContext(new ExternalContextRequest(true, null, List.of()))
                    .build());

            assertThat(context.isEmpty()).isTrue();
        }
    }

    private static McpContextSource source(String name) {
        List<String> command = McpStdioClientTest.serverCommand();
        return new McpContextSource(name, true, command.get(0), command.subList(1, command.size()),
                "echo", "query", null, Map.of(), Duration.ofSeconds(10), 8_000);
    }
}
