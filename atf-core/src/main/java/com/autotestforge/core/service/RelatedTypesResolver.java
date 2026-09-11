package com.autotestforge.core.service;

import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.ParameterInfo;
import com.autotestforge.core.domain.ScannedProject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the project types an LLM needs to see to write a test for a class:
 * first the collaborators (field / constructor dependencies from the
 * dependency graph), then every project type mentioned in the public method
 * signatures (return types, parameters, generics, thrown exceptions). Knowing
 * a collaborator's API lets the model stub it correctly; knowing a value type's
 * shape lets it construct fixtures that compile.
 */
final class RelatedTypesResolver {

    private static final Pattern TYPE_TOKEN =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private RelatedTypesResolver() {
    }

    static List<JavaClassInfo> resolve(JavaClassInfo target, ScannedProject project, int maxTypes) {
        if (project == null || maxTypes <= 0) {
            return List.of();
        }
        Map<String, JavaClassInfo> byFqn = new HashMap<>();
        Map<String, String> uniqueSimpleNames = new HashMap<>();
        for (JavaClassInfo classInfo : project.classes()) {
            byFqn.put(classInfo.fullyQualifiedName(), classInfo);
            uniqueSimpleNames.merge(classInfo.className(), classInfo.fullyQualifiedName(), (a, b) -> "");
        }

        Set<String> related = new LinkedHashSet<>();
        if (project.dependencyGraph() != null) {
            project.dependencyGraph().dependenciesOf(target.fullyQualifiedName()).stream()
                    .sorted()
                    .forEach(related::add);
        }
        for (MethodInfo method : target.publicMethods()) {
            addMentionedTypes(method.returnType(), target, byFqn, uniqueSimpleNames, related);
            for (ParameterInfo parameter : method.parameters()) {
                addMentionedTypes(parameter.type(), target, byFqn, uniqueSimpleNames, related);
            }
            for (String exception : method.thrownExceptions()) {
                addMentionedTypes(exception, target, byFqn, uniqueSimpleNames, related);
            }
        }
        related.remove(target.fullyQualifiedName());

        List<JavaClassInfo> result = new ArrayList<>();
        for (String fqn : related) {
            JavaClassInfo classInfo = byFqn.get(fqn);
            if (classInfo != null) {
                result.add(classInfo);
            }
            if (result.size() >= maxTypes) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static void addMentionedTypes(String typeExpression,
                                          JavaClassInfo owner,
                                          Map<String, JavaClassInfo> byFqn,
                                          Map<String, String> uniqueSimpleNames,
                                          Set<String> sink) {
        if (typeExpression == null || typeExpression.isBlank()) {
            return;
        }
        Matcher matcher = TYPE_TOKEN.matcher(typeExpression);
        while (matcher.find()) {
            resolveToken(matcher.group(), owner, byFqn, uniqueSimpleNames).ifPresent(sink::add);
        }
    }

    private static Optional<String> resolveToken(String token,
                                                 JavaClassInfo owner,
                                                 Map<String, JavaClassInfo> byFqn,
                                                 Map<String, String> uniqueSimpleNames) {
        if (byFqn.containsKey(token)) {
            return Optional.of(token);
        }
        if (token.contains(".")) {
            return Optional.empty();   // fully qualified but foreign (java.util.List, ...)
        }
        String samePackage = owner.packageName().isBlank() ? token : owner.packageName() + "." + token;
        if (byFqn.containsKey(samePackage)) {
            return Optional.of(samePackage);
        }
        for (String imported : owner.imports()) {
            if (imported.endsWith("." + token) && byFqn.containsKey(imported)) {
                return Optional.of(imported);
            }
        }
        String unique = uniqueSimpleNames.getOrDefault(token, "");
        return unique.isBlank() ? Optional.empty() : Optional.of(unique);
    }
}
