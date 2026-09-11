package com.autotestforge.mcp;

import com.autotestforge.core.domain.ExternalContextRequest;
import com.autotestforge.core.domain.ExternalContextSnippet;
import com.autotestforge.core.domain.ExternalContextSourceRequest;
import com.autotestforge.core.domain.ExternalTestContext;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.port.out.ExternalContextPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retrieves class-specific business/TMS context from configured MCP tools.
 * <p>
 * One MCP server process is kept per distinct source configuration and reused
 * for every class of a run (starting {@code npx ...} per class would dominate
 * the runtime). Sources supplied per run by a driving adapter are released
 * when the run finishes; statically configured ones live as long as this
 * provider and are terminated by {@link #close()}.
 */
public class McpExternalContextProvider implements ExternalContextPort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpExternalContextProvider.class);

    private final List<McpContextSource> sources;
    private final Map<McpContextSource, McpStdioClient> clients = new ConcurrentHashMap<>();

    public McpExternalContextProvider(List<McpContextSource> sources) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    @Override
    public ExternalTestContext fetchContext(JavaClassInfo classInfo, TestGenerationRequest request) {
        ExternalContextRequest contextRequest = request.externalContext();
        if (!contextRequest.enabled()) {
            return ExternalTestContext.empty();
        }

        List<McpContextSource> selectedSources = selectedSources(contextRequest);
        if (selectedSources.isEmpty()) {
            log.warn("External context was requested, but no configured MCP sources matched {}",
                    contextRequest.sources());
            return ExternalTestContext.empty();
        }

        List<ExternalContextSnippet> snippets = new ArrayList<>();
        for (McpContextSource source : selectedSources) {
            fetchFromSource(source, classInfo, request).ifPresent(snippets::add);
        }
        return snippets.isEmpty() ? ExternalTestContext.empty() : new ExternalTestContext(snippets);
    }

    @Override
    public void onRunFinished(TestGenerationRequest request) {
        request.externalContext().mcpSources().stream()
                .map(this::toMcpSource)
                .forEach(this::evict);
    }

    /** Sources currently configured (static ones only; per-run sources are supplied with the request). */
    public List<McpContextSource> configuredSources() {
        return sources;
    }

    private List<McpContextSource> selectedSources(ExternalContextRequest contextRequest) {
        List<McpContextSource> allSources = new ArrayList<>(sources);
        allSources.addAll(contextRequest.mcpSources().stream()
                .map(this::toMcpSource)
                .toList());
        return allSources.stream()
                .filter(McpContextSource::configured)
                .filter(source -> contextRequest.sources().isEmpty()
                        || contextRequest.sources().contains(source.name()))
                .toList();
    }

    private McpContextSource toMcpSource(ExternalContextSourceRequest source) {
        return new McpContextSource(source.name(), source.enabled(), source.command(), source.args(),
                source.toolName(), source.queryArgument(), source.queryTemplate(), source.arguments(),
                source.timeout(), source.maxChars());
    }

    private java.util.Optional<ExternalContextSnippet> fetchFromSource(McpContextSource source,
                                                                        JavaClassInfo classInfo,
                                                                        TestGenerationRequest request) {
        String template = request.externalContext().query() == null
                ? source.queryTemplate()
                : request.externalContext().query();
        String query = McpQueryTemplate.render(template, classInfo, request.projectPath());
        Map<String, Object> arguments = new LinkedHashMap<>(source.arguments());
        arguments.put(source.queryArgument(), query);

        try {
            McpStdioClient client = client(source);
            String content = client.callTool(source.toolName(), arguments);
            if (content.isBlank()) {
                log.info("MCP source '{}' returned nothing for {}", source.name(), classInfo.fullyQualifiedName());
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new ExternalContextSnippet(
                    source.name(), title(source, classInfo), trim(content, source.maxChars())));
        } catch (RuntimeException e) {
            log.warn("Skipping MCP context source '{}' for {}: {}",
                    source.name(), classInfo.fullyQualifiedName(), e.getMessage());
            evict(source);
            return java.util.Optional.empty();
        }
    }

    private McpStdioClient client(McpContextSource source) {
        McpStdioClient existing = clients.get(source);
        if (existing != null && !existing.isAlive()) {
            evict(source);
        }
        return clients.computeIfAbsent(source, s -> {
            McpStdioClient client = new McpStdioClient(s.commandLine(), s.timeout());
            log.info("Connected MCP source '{}' ({}, protocol {})", s.name(), client.serverInfo(),
                    client.protocolVersion());
            return client;
        });
    }

    private void evict(McpContextSource source) {
        McpStdioClient client = clients.remove(source);
        if (client != null) {
            client.close();
        }
    }

    private String title(McpContextSource source, JavaClassInfo classInfo) {
        return "MCP " + source.toolName() + " results for " + classInfo.fullyQualifiedName();
    }

    private String trim(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + System.lineSeparator() + "... [truncated]";
    }

    @Override
    public void close() {
        List<McpContextSource> keys = new ArrayList<>(clients.keySet());
        keys.forEach(this::evict);
    }
}
