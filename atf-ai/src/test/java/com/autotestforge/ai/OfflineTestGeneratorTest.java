package com.autotestforge.ai;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineTestGeneratorTest {

    private final OfflineTestGenerator generator = new OfflineTestGenerator();

    @Test
    @DisplayName("offline generator emits syntactically valid, deterministic smoke tests")
    void generate_shouldProduceParsableTestClass() {
        JavaClassInfo classInfo = new JavaClassInfo("com.acme", "PriceCalculator", ClassKind.CLASS, false,
                "public class PriceCalculator {}", "", List.of(),
                List.of(new MethodInfo("total", "long", List.of(), List.of(), "", List.of(), false),
                        new MethodInfo("discount", "long", List.of(), List.of(), "", List.of(), false)),
                List.of(), List.of(), Path.of("PriceCalculator.java"), null);

        GeneratedTestFile test = generator.generate(classInfo, "offline");

        assertThat(test.packageName()).isEqualTo("com.acme");
        assertThat(test.className()).isEqualTo("PriceCalculatorTest");
        ParseResult<CompilationUnit> parsed = new JavaParser().parse(test.sourceCode());
        assertThat(parsed.isSuccessful())
                .withFailMessage("generated code must parse, problems: %s", parsed.getProblems())
                .isTrue();
        assertThat(test.sourceCode())
                .contains("class_shouldBeLoadableAndConcrete")
                .contains("total_shouldBeDeclaredAsPublicMethod")
                .contains("discount_shouldBeDeclaredAsPublicMethod");
        // deterministic: same input, same output
        assertThat(generator.generate(classInfo, "offline").sourceCode()).isEqualTo(test.sourceCode());
    }
}
