package com.autotestforge.core.service;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.TestGenerationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TargetSelectorTest {

    private static final Path PROJECT = Path.of("/repo");

    private final JavaClassInfo service = classInfo("com.acme.service", "OrderService", ClassKind.CLASS, List.of());
    private final JavaClassInfo util = classInfo("com.acme.util", "TextUtils", ClassKind.CLASS, List.of());

    @Test
    @DisplayName("wildcards match simple and fully qualified names")
    void matches_shouldSupportWildcards() {
        assertThat(TargetSelector.matches(service, "*Service")).isTrue();
        assertThat(TargetSelector.matches(service, "com.acme.service.*")).isTrue();
        assertThat(TargetSelector.matches(service, "com.acme.*.Order?ervice")).isTrue();
        assertThat(TargetSelector.matches(service, "com.acme.util.*")).isFalse();
        assertThat(TargetSelector.matches(service, "OrderService")).isTrue();
        assertThat(TargetSelector.matches(service, "com.acme.service.OrderService")).isTrue();
        assertThat(TargetSelector.matches(service, "OrderServ")).isFalse();
    }

    @Test
    @DisplayName("exclusions are applied after inclusions")
    void select_shouldApplyIncludesThenExcludes() {
        TestGenerationRequest request = TestGenerationRequest.builder(PROJECT)
                .includedClasses(List.of("com.acme.*"))
                .excludedClasses(List.of("*Utils"))
                .build();

        List<JavaClassInfo> selected = TargetSelector.select(List.of(service, util), request);

        assertThat(selected).containsExactly(service);
        assertThat(TargetSelector.skipReason(util, request)).contains("excluded by filter");
    }

    @Test
    @DisplayName("skip reasons explain why a type is not a target")
    void skipReason_shouldExplainEveryRule() {
        TestGenerationRequest request = TestGenerationRequest.builder(PROJECT)
                .includedClasses(List.of("Nope")).build();

        assertThat(TargetSelector.skipReason(
                classInfo("com.acme", "Repo", ClassKind.INTERFACE, List.of()), request)).contains("interface");
        assertThat(TargetSelector.skipReason(new JavaClassInfo("com.acme", "Base", ClassKind.CLASS, true, "", "",
                List.of(), List.of(method()), List.of(), List.of(), PROJECT.resolve("Base.java"), null), request))
                .contains("abstract class");
        assertThat(TargetSelector.skipReason(
                classInfo("com.acme", "Config", ClassKind.CLASS, List.of("Configuration")), request))
                .hasValueSatisfying(reason -> assertThat(reason).contains("@Configuration"));
        assertThat(TargetSelector.skipReason(new JavaClassInfo("com.acme", "Dto", ClassKind.RECORD, false, "", "",
                List.of(), List.of(), List.of(), List.of(), PROJECT.resolve("Dto.java"), null), request))
                .contains("no public methods");
        assertThat(TargetSelector.skipReason(service, request)).contains("not matched by class filter");
        assertThat(TargetSelector.skipReason(service, TestGenerationRequest.builder(PROJECT).build())).isEmpty();
    }

    private static JavaClassInfo classInfo(String pkg, String name, ClassKind kind, List<String> annotations) {
        return new JavaClassInfo(pkg, name, kind, false, "", "", annotations, List.of(method()),
                List.of(), List.of(), PROJECT.resolve(name + ".java"), null);
    }

    private static MethodInfo method() {
        return new MethodInfo("run", "void", List.of(), List.of(), "", List.of(), false);
    }
}
