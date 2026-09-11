package com.autotestforge.core.domain;

import java.util.List;

/**
 * Everything besides the class itself that the AI generator may use to design
 * tests for one class.
 *
 * @param externalContext business / TMS fragments retrieved from external systems (may be empty)
 * @param relatedTypes    project types the class under test collaborates with or exposes in its API
 *                        (collaborators to stub, value objects to construct); ordered by relevance
 */
public record GenerationContext(ExternalTestContext externalContext, List<JavaClassInfo> relatedTypes) {

    private static final GenerationContext EMPTY = new GenerationContext(ExternalTestContext.empty(), List.of());

    public GenerationContext {
        externalContext = externalContext == null ? ExternalTestContext.empty() : externalContext;
        relatedTypes = relatedTypes == null ? List.of() : List.copyOf(relatedTypes);
    }

    public static GenerationContext empty() {
        return EMPTY;
    }

    public static GenerationContext of(ExternalTestContext externalContext) {
        return new GenerationContext(externalContext, List.of());
    }

    public boolean hasExternalContext() {
        return !externalContext.isEmpty();
    }

    public boolean hasRelatedTypes() {
        return !relatedTypes.isEmpty();
    }
}
