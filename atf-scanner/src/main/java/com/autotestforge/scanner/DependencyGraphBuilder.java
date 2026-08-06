package com.autotestforge.scanner;

import com.autotestforge.core.domain.DependencyGraph;
import com.autotestforge.core.domain.FieldDependency;
import com.autotestforge.core.domain.JavaClassInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the intra-project dependency graph: an edge {@code A -> B} means
 * class A holds a field (or record component) of project class B.
 * Types are resolved by fully qualified name, then same-package lookup,
 * then explicit imports, then unique simple name.
 */
final class DependencyGraphBuilder {

    private DependencyGraphBuilder() {
    }

    static DependencyGraph build(List<JavaClassInfo> classes) {
        Set<String> projectFqns = classes.stream()
                .map(JavaClassInfo::fullyQualifiedName)
                .collect(Collectors.toSet());

        Map<String, String> uniqueSimpleNames = new HashMap<>();
        for (JavaClassInfo classInfo : classes) {
            uniqueSimpleNames.merge(classInfo.className(), classInfo.fullyQualifiedName(),
                    (a, b) -> "");   // ambiguous simple names are not used for resolution
        }

        DependencyGraph graph = new DependencyGraph();
        classes.forEach(c -> graph.addClass(c.fullyQualifiedName()));

        for (JavaClassInfo classInfo : classes) {
            for (FieldDependency dependency : classInfo.dependencies()) {
                resolve(dependency.type(), classInfo, projectFqns, uniqueSimpleNames)
                        .ifPresent(target -> graph.addDependency(classInfo.fullyQualifiedName(), target));
            }
        }
        return graph;
    }

    private static Optional<String> resolve(String type,
                                            JavaClassInfo owner,
                                            Set<String> projectFqns,
                                            Map<String, String> uniqueSimpleNames) {
        String raw = stripGenerics(type);
        if (projectFqns.contains(raw)) {
            return Optional.of(raw);
        }
        if (raw.contains(".")) {
            return Optional.empty();   // fully qualified but not a project class
        }
        String samePackage = owner.packageName().isBlank() ? raw : owner.packageName() + "." + raw;
        if (projectFqns.contains(samePackage)) {
            return Optional.of(samePackage);
        }
        for (String imported : owner.imports()) {
            if (imported.endsWith("." + raw) && projectFqns.contains(imported)) {
                return Optional.of(imported);
            }
        }
        String bySimpleName = uniqueSimpleNames.getOrDefault(raw, "");
        return bySimpleName.isBlank() ? Optional.empty() : Optional.of(bySimpleName);
    }

    private static String stripGenerics(String type) {
        int idx = type.indexOf('<');
        return (idx < 0 ? type : type.substring(0, idx)).strip();
    }
}
