package com.autotestforge.core.service;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.TestGenerationRequest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which scanned types are worth generating tests for.
 * <p>
 * A type is a target when it is a concrete class, enum or record with public
 * behavior that is neither an application entry point nor a Spring
 * configuration, and it passes the request's include / exclude filters.
 * Filters accept simple names, fully qualified names and {@code *} wildcards
 * (for example {@code com.acme.service.*} or {@code *Service}).
 */
final class TargetSelector {

    /** Annotations marking types that are wired by a framework rather than unit-tested. */
    private static final Set<String> FRAMEWORK_ENTRY_ANNOTATIONS = Set.of(
            "SpringBootApplication", "Configuration", "SpringBootConfiguration", "AutoConfiguration");

    private TargetSelector() {
    }

    static List<JavaClassInfo> select(List<JavaClassInfo> classes, TestGenerationRequest request) {
        return classes.stream()
                .filter(classInfo -> skipReason(classInfo, request).isEmpty())
                .toList();
    }

    /** Empty when the type is a target; otherwise a short human-readable reason. */
    static Optional<String> skipReason(JavaClassInfo classInfo, TestGenerationRequest request) {
        if (classInfo.kind() == ClassKind.INTERFACE) {
            return Optional.of("interface");
        }
        if (classInfo.isAbstract()) {
            return Optional.of("abstract class");
        }
        Optional<String> frameworkAnnotation = classInfo.annotations().stream()
                .filter(FRAMEWORK_ENTRY_ANNOTATIONS::contains)
                .findFirst();
        if (frameworkAnnotation.isPresent()) {
            return Optional.of("@" + frameworkAnnotation.get() + " is framework wiring, not unit-testable logic");
        }
        if (classInfo.publicMethods().isEmpty()) {
            return Optional.of("no public methods");
        }
        if (!request.includedClasses().isEmpty() && !matchesAny(classInfo, request.includedClasses())) {
            return Optional.of("not matched by class filter");
        }
        if (matchesAny(classInfo, request.excludedClasses())) {
            return Optional.of("excluded by filter");
        }
        return Optional.empty();
    }

    static boolean matchesAny(JavaClassInfo classInfo, List<String> patterns) {
        return patterns.stream().anyMatch(pattern -> matches(classInfo, pattern));
    }

    static boolean matches(JavaClassInfo classInfo, String pattern) {
        if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) {
            return pattern.equals(classInfo.className()) || pattern.equals(classInfo.fullyQualifiedName());
        }
        Pattern regex = wildcardToRegex(pattern);
        return regex.matcher(classInfo.className()).matches()
                || regex.matcher(classInfo.fullyQualifiedName()).matches();
    }

    private static Pattern wildcardToRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
