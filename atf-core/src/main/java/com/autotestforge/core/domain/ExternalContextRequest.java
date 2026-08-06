package com.autotestforge.core.domain;

import java.util.List;

/**
 * Per-run controls for retrieving external business and test-management
 * context. Driving adapters fill this from CLI flags or REST request fields.
 */
public record ExternalContextRequest(
        boolean enabled,
        String query,
        List<String> sources,
        List<ExternalContextSourceRequest> mcpSources,
        List<ExternalContextSnippet> inlineSnippets) {

    private static final ExternalContextRequest DISABLED =
            new ExternalContextRequest(false, null, List.of(), List.of(), List.of());

    public ExternalContextRequest(boolean enabled, String query, List<String> sources) {
        this(enabled, query, sources, List.of(), List.of());
    }

    public ExternalContextRequest {
        query = query == null || query.isBlank() ? null : query.strip();
        sources = sources == null
                ? List.of()
                : sources.stream()
                .filter(source -> source != null && !source.isBlank())
                .map(source -> source.strip().toLowerCase())
                .distinct()
                .toList();
        mcpSources = mcpSources == null ? List.of() : List.copyOf(mcpSources);
        inlineSnippets = inlineSnippets == null ? List.of() : List.copyOf(inlineSnippets);
        enabled = enabled || !mcpSources.isEmpty() || !inlineSnippets.isEmpty();
    }

    public static ExternalContextRequest disabled() {
        return DISABLED;
    }
}
