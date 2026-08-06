package com.autotestforge.core.domain;

import java.util.List;

/** External business and TMS context collected for one class under test. */
public record ExternalTestContext(List<ExternalContextSnippet> snippets) {

    private static final ExternalTestContext EMPTY = new ExternalTestContext(List.of());

    public ExternalTestContext {
        snippets = snippets == null ? List.of() : List.copyOf(snippets);
    }

    public static ExternalTestContext empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return snippets.isEmpty();
    }
}
