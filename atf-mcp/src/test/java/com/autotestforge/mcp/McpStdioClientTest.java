package com.autotestforge.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpStdioClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    @DisplayName("newline-delimited JSON-RPC (MCP stdio transport): initialize, tools/list and tools/call work")
    void callTool_shouldTalkNewlineDelimitedJsonRpc() {
        try (McpStdioClient client = new McpStdioClient(serverCommand(), TIMEOUT)) {
            assertThat(client.serverInfo()).isEqualTo("fake-mcp 1.0");
            assertThat(client.protocolVersion()).isNotBlank();
            assertThat(client.listTools()).extracting(McpStdioClient.McpToolDescriptor::name)
                    .containsExactly("echo", "slow", "fail", "structured");

            String content = client.callTool("echo", Map.of("query", "rules for OrderService"));

            assertThat(content).isEqualTo("echo: rules for OrderService");
        }
    }

    @Test
    @DisplayName("servers that still use LSP-style Content-Length framing are supported")
    void callTool_shouldSupportContentLengthFraming() {
        try (McpStdioClient client = new McpStdioClient(serverCommand("--lsp"), TIMEOUT)) {
            assertThat(client.callTool("echo", Map.of("query", "lsp"))).isEqualTo("echo: lsp");
        }
    }

    @Test
    @DisplayName("stray stdout lines, server-initiated requests and notifications do not break the session")
    void callTool_shouldIgnoreNoiseAndAnswerServerRequests() {
        try (McpStdioClient client = new McpStdioClient(serverCommand("--noisy"), TIMEOUT)) {
            assertThat(client.callTool("echo", Map.of("query", "one"))).isEqualTo("echo: one");
            assertThat(client.callTool("echo", Map.of("query", "two"))).isEqualTo("echo: two");
        }
    }

    @Test
    @DisplayName("tool errors are surfaced as McpToolException with the tool output")
    void callTool_shouldThrow_whenToolReportsError() {
        try (McpStdioClient client = new McpStdioClient(serverCommand(), TIMEOUT)) {
            assertThatThrownBy(() -> client.callTool("fail", Map.of()))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("tool exploded");
        }
    }

    @Test
    @DisplayName("structuredContent is returned when the tool emits no text content")
    void callTool_shouldFallBackToStructuredContent() {
        try (McpStdioClient client = new McpStdioClient(serverCommand(), TIMEOUT)) {
            assertThat(client.callTool("structured", Map.of())).contains("\"answer\":42");
        }
    }

    @Test
    @DisplayName("a timed-out request does not poison later calls on the same session")
    void callTool_shouldRemainUsable_afterTimeout() {
        try (McpStdioClient client = new McpStdioClient(serverCommand(), Duration.ofMillis(300))) {
            assertThatThrownBy(() -> client.callTool("slow", Map.of()))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("Timed out");

            assertThat(client.callTool("echo", Map.of("query", "still alive"))).isEqualTo("echo: still alive");
        }
    }

    @Test
    @DisplayName("a closed client rejects further calls instead of hanging")
    void callTool_shouldFailFast_whenClosed() {
        McpStdioClient client = new McpStdioClient(serverCommand(), TIMEOUT);
        client.close();

        assertThat(client.isAlive()).isFalse();
        assertThatThrownBy(() -> client.callTool("echo", Map.of()))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("an unstartable command fails with a clear error")
    void constructor_shouldThrow_whenCommandCannotStart() {
        assertThatThrownBy(() -> new McpStdioClient(List.of("definitely-not-a-real-binary-atf"), TIMEOUT))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("Failed to start MCP server");
    }

    static List<String> serverCommand(String... flags) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(FakeMcpServer.class.getName());
        command.addAll(List.of(flags));
        return command;
    }
}
