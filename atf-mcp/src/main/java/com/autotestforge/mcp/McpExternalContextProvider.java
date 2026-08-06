package com.autotestforge.mcp;

import com.autotestforge.core.domain.ExternalContextRequest;
import com.autotestforge.core.domain.ExternalContextSnippet;
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

/** Retrieves class-specific business/TMS context from configured MCP tools. */
public class McpExternalContextProvider implements ExternalContextPort {

    private static final Logger log = LoggerFactory.getLogger(McpExternalContextProvider.class);

    private final List<McpContextSource> sources;

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
            fetchFromSource(source, classInfo, request).stream()
                    .findFirst()
                    .ifPresent(snippets::add);
        }
        return snippets.isEmpty() ? ExternalTestContext.empty() : new ExternalTestContext(snippets);
    }

    private List<McpContextSource> selectedSources(ExternalContextRequest contextRequest) {
        return sources.stream()
                .filter(McpContextSource::configured)
                .filter(source -> contextRequest.sources().isEmpty()
                        || contextRequest.sources().contains(source.name()))
                .toList();
    }

    private List<ExternalContextSnippet> fetchFromSource(McpContextSource source,
                                                         JavaClassInfo classInfo,
                                                         TestGenerationRequest request) {
        String template = request.externalContext().query() == null
                ? source.queryTemplate()
                : request.externalContext().query();
        String query = McpQueryTemplate.render(template, classInfo, request.projectPath());
        Map<String, Object> arguments = new LinkedHashMap<>(source.arguments());
        arguments.put(source.queryArgument(), query);

        try (McpStdioClient client = new McpStdioClient(source.commandLine(), source.timeout())) {
            String content = client.callTool(source.toolName(), arguments);
            if (content.isBlank()) {
                return List.of();
            }
            return List.of(new ExternalContextSnippet(source.name(), title(source, classInfo), trim(content, source.maxChars())));
        } catch (RuntimeException e) {
            log.warn("Skipping MCP context source '{}' for {}: {}",
                    source.name(), classInfo.fullyQualifiedName(), e.getMessage());
            return List.of();
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
}
