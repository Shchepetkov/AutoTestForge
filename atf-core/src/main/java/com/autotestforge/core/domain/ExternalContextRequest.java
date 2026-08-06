package com.autotestforge.core.domain;

import java.util.List;

/**
 * Per-run controls for retrieving external business and test-management
 * context. Driving adapters fill this from CLI flags or REST request fields.
 */
public record ExternalContextRequest(
        boolean enabled,
        String query,
        List<String> sources) {

    private static final ExternalContextRequest DISABLED = new ExternalContextRequest(false, null, List.of());

    public ExternalContextRequest {
        query = query == null || query.isBlank() ? null : query.strip();
        sources = sources == null
                ? List.of()
                : sources.stream()
                .filter(source -> source != null && !source.isBlank())
                .map(source -> source.strip().toLowerCase())
                .distinct()
                .toList();
    }

    public static ExternalContextRequest disabled() {
        return DISABLED;
    }
}
