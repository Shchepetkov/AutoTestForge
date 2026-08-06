package com.autotestforge.core.domain;

/**
 * A collaborator the class depends on (constructor-injected or field-injected).
 * These are prime candidates for Mockito mocks in the generated tests.
 *
 * @param fieldName name of the field holding the dependency
 * @param type      dependency type (fully qualified when resolvable)
 * @param injected  true when the dependency is injected via constructor or {@code @Autowired}
 */
public record FieldDependency(String fieldName, String type, boolean injected) {
}
