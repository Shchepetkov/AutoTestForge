package com.autotestforge.web.api;

import com.autotestforge.ai.OfflineTestGenerator;
import com.autotestforge.ai.RoutingTestGenerator;
import com.autotestforge.core.port.out.AiTestGeneratorPort;
import com.autotestforge.mcp.McpContextSource;
import com.autotestforge.mcp.McpExternalContextProvider;
import com.autotestforge.spring.AtfProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CapabilitiesController.class)
@Import(CapabilitiesControllerTest.Fixtures.class)
class CapabilitiesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/capabilities exposes providers, configured sources and defaults")
    void capabilities_shouldDescribeServer() throws Exception {
        mockMvc.perform(get("/api/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers").value(org.hamcrest.Matchers.contains("offline", "openai")))
                .andExpect(jsonPath("$.defaultProvider").value("offline"))
                .andExpect(jsonPath("$.configuredContextSources[0]").value("confluence"))
                .andExpect(jsonPath("$.defaultParallelism").value(3))
                .andExpect(jsonPath("$.defaultMaxFixAttempts").value(1))
                .andExpect(jsonPath("$.version").value("0.2.0"));
    }

    @Test
    @DisplayName("POST /api/mcp/tools reports unreachable servers as 502 with the reason")
    void mcpTools_shouldReportUnstartableServer() throws Exception {
        mockMvc.perform(post("/api/mcp/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"command": "definitely-not-a-real-binary-atf", "args": ["--x"], "timeoutSeconds": 2}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Failed to start MCP server")));

        mockMvc.perform(post("/api/mcp/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\": \" \"}"))
                .andExpect(status().isBadRequest());
    }

    @TestConfiguration
    static class Fixtures {

        @Bean
        AiTestGeneratorPort aiTestGenerator() {
            return new RoutingTestGenerator(
                    Map.of("offline", new OfflineTestGenerator(), "openai", new OfflineTestGenerator()), "offline");
        }

        @Bean
        McpExternalContextProvider externalContextProvider() {
            return new McpExternalContextProvider(List.of(new McpContextSource("confluence", true, "npx",
                    List.of("-y", "x"), "search", "query", null, Map.of(), Duration.ofSeconds(5), 1000)));
        }

        @Bean
        AtfProperties atfProperties() {
            return new AtfProperties(
                    new AtfProperties.Llm("offline", 0.2, 1, 0, null, null, null),
                    new AtfProperties.Generation(3, false),
                    new AtfProperties.Validation(1, false, "m", "g", Duration.ofMinutes(1), Path.of("/tmp")),
                    new AtfProperties.Context(true, List.of()));
        }
    }
}
