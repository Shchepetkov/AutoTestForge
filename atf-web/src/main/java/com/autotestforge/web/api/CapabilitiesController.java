package com.autotestforge.web.api;

import com.autotestforge.ai.RoutingTestGenerator;
import com.autotestforge.core.port.out.AiTestGeneratorPort;
import com.autotestforge.mcp.McpContextSource;
import com.autotestforge.mcp.McpExternalContextProvider;
import com.autotestforge.mcp.McpStdioClient;
import com.autotestforge.mcp.McpToolException;
import com.autotestforge.spring.AtfProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Lets the UI adapt to the server configuration: available LLM providers,
 * statically configured MCP sources, default generation settings, and a probe
 * that lists the tools an MCP server exposes before a run is started.
 */
@RestController
@RequestMapping("/api")
public class CapabilitiesController {

    private final AiTestGeneratorPort aiTestGenerator;
    private final McpExternalContextProvider externalContextProvider;
    private final AtfProperties properties;

    public CapabilitiesController(AiTestGeneratorPort aiTestGenerator,
                                  McpExternalContextProvider externalContextProvider,
                                  AtfProperties properties) {
        this.aiTestGenerator = aiTestGenerator;
        this.externalContextProvider = externalContextProvider;
        this.properties = properties;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        List<String> providers = new ArrayList<>();
        String defaultProvider = properties.llm().provider();
        if (aiTestGenerator instanceof RoutingTestGenerator router) {
            providers.addAll(new TreeSet<>(router.providers()));
            defaultProvider = router.defaultProvider();
        }
        return new Capabilities(
                providers,
                defaultProvider,
                externalContextProvider.configuredSources().stream().map(McpContextSource::name).toList(),
                properties.generation().parallelism(),
                properties.generation().overwriteExisting(),
                properties.validation().maxFixAttempts(),
                "0.2.0");
    }

    /** Starts the given MCP server once, lists its tools and shuts it down again. */
    @PostMapping("/mcp/tools")
    public ResponseEntity<McpToolsResponse> mcpTools(@Valid @RequestBody McpProbeRequest request) {
        List<String> command = new ArrayList<>();
        command.add(request.command().strip());
        if (request.args() != null) {
            request.args().stream().filter(arg -> arg != null && !arg.isBlank()).forEach(command::add);
        }
        Duration timeout = Duration.ofSeconds(request.timeoutSeconds() == null || request.timeoutSeconds() <= 0
                ? 30 : request.timeoutSeconds());
        try (McpStdioClient client = new McpStdioClient(command, timeout)) {
            List<McpToolsResponse.Tool> tools = client.listTools().stream()
                    .map(tool -> new McpToolsResponse.Tool(tool.name(), tool.description(),
                            new ArrayList<>(tool.inputSchemaOrEmpty().path("properties").properties().stream()
                                    .map(entry -> entry.getKey()).toList())))
                    .toList();
            return ResponseEntity.ok(new McpToolsResponse(client.serverInfo(), client.protocolVersion(), tools, null));
        } catch (McpToolException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new McpToolsResponse(null, null, List.of(), e.getMessage()));
        }
    }

    public record Capabilities(List<String> providers,
                               String defaultProvider,
                               List<String> configuredContextSources,
                               int defaultParallelism,
                               boolean defaultOverwrite,
                               int defaultMaxFixAttempts,
                               String version) {
    }

    public record McpProbeRequest(@NotBlank String command, List<String> args, Integer timeoutSeconds) {
    }

    public record McpToolsResponse(String serverInfo, String protocolVersion, List<Tool> tools, String error) {

        public record Tool(String name, String description, List<String> arguments) {
        }
    }
}
