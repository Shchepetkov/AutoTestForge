package com.autotestforge.core.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Directed graph of intra-project class dependencies
 * ("class A uses class B"), keyed by fully qualified names.
 */
public final class DependencyGraph {

    private final Map<String, Set<String>> adjacency = new HashMap<>();

    public void addClass(String classFqn) {
        adjacency.computeIfAbsent(classFqn, k -> new HashSet<>());
    }

    public void addDependency(String fromFqn, String toFqn) {
        addClass(fromFqn);
        addClass(toFqn);
        adjacency.get(fromFqn).add(toFqn);
    }

    /** Classes that {@code classFqn} directly depends on. */
    public Set<String> dependenciesOf(String classFqn) {
        return Collections.unmodifiableSet(adjacency.getOrDefault(classFqn, Set.of()));
    }

    /** Classes that directly depend on {@code classFqn}. */
    public Set<String> dependentsOf(String classFqn) {
        Set<String> dependents = new HashSet<>();
        adjacency.forEach((from, targets) -> {
            if (targets.contains(classFqn)) {
                dependents.add(from);
            }
        });
        return dependents;
    }

    public Set<String> classes() {
        return Collections.unmodifiableSet(adjacency.keySet());
    }

    public int size() {
        return adjacency.size();
    }
}
