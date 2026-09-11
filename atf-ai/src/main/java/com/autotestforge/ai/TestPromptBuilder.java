package com.autotestforge.ai;

import com.autotestforge.core.domain.ExternalContextSnippet;
import com.autotestforge.core.domain.ExternalTestContext;
import com.autotestforge.core.domain.FieldDependency;
import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.GenerationContext;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.TestFailure;
import com.autotestforge.core.domain.ValidationResult;

import java.util.stream.Collectors;

/**
 * Prompt engineering for test generation. Builds a self-contained prompt with
 * the full class source, its public API, collaborators to mock, the shape of
 * related project types (so stubs and fixtures compile), optional external
 * business context and strict output requirements (AAA structure, naming
 * convention, edge cases, compile-ready single code block).
 */
public class TestPromptBuilder {

    private static final int MAX_LOG_CHARS = 6_000;
    private static final int MAX_EXTERNAL_CONTEXT_CHARS = 12_000;
    private static final int MAX_RELATED_TYPE_SOURCE_CHARS = 1_800;
    private static final int MAX_RELATED_SECTION_CHARS = 10_000;
    private static final int MAX_SOURCE_CHARS = 40_000;

    /** Role and non-negotiable rules; sent as the system message when the provider supports it. */
    public static final String SYSTEM_PROMPT = """
            You are a senior Java test engineer. You write complete, production-quality JUnit 5 test classes \
            that compile as-is and pass against the code they test. You use only JUnit 5 (org.junit.jupiter), \
            Mockito and AssertJ. You never invent classes, methods or fields that are not shown to you. \
            You answer with exactly one fenced ```java code block and nothing else.""";

    private static final String GENERATION_TEMPLATE = """
            Write a complete, production-quality JUnit 5 test class for the class described below.

            ## Class under test
            Package: %{packageName}
            Type: %{kind} %{className}%{springNote}
            %{javadocSection}
            Full source code:
            ```java
            %{sourceCode}
            ```

            ## Public API that must be covered
            %{methodList}

            ## Collaborators to mock with Mockito
            %{dependencyList}

            %{relatedTypesSection}

            %{externalContextSection}

            ## Hard requirements
            1. Use JUnit 5 (org.junit.jupiter), Mockito and AssertJ (assertThat) only.
            2. Follow the Arrange-Act-Assert structure; separate the sections with blank lines.
            3. Name tests `methodName_shouldExpectedBehavior_whenCondition`.
            4. For EVERY public method cover: the happy path, at least one edge case \
            (null / empty / zero / boundary values) and, where the code can fail, the error scenario \
            (use assertThatThrownBy).
            5. The test class MUST be declared in package `%{packageName}` and named `%{testClassName}`.
            6. Mock only the collaborators listed above (use @ExtendWith(MockitoExtension.class), @Mock and \
            @InjectMocks where appropriate). Do not mock value objects, records or enums - construct them.
            7. %{styleHint}
            8. Use only constructors, methods and fields that appear in the sources shown above; when related \
            project types are listed, call exactly the API they expose (respect Optional return types, record \
            components and checked exceptions).
            9. The code must compile as-is: no TODOs, no placeholders, no references to classes that are \
            not part of the class under test, its collaborators, the related types, the JDK, JUnit, Mockito or AssertJ.
            10. If external business/TMS context is present, analyze it before writing tests and add a concise \
            class-level Javadoc section named "Business/TMS coverage" that lists covered scenarios and any \
            important gaps that cannot be verified from this class alone.

            ## Output format
            Return EXACTLY ONE fenced Java code block containing the full test class source \
            (package declaration, imports, class). No explanations outside the code block.
            """;

    private static final String FIX_TEMPLATE = """
            The JUnit 5 test class you generated earlier fails. Fix it and return the corrected class.

            ## Class under test
            ```java
            %{sourceCode}
            ```

            ## Current (failing) test class
            ```java
            %{previousTest}
            ```

            ## Failures
            %{failures}

            %{relatedTypesSection}

            %{externalContextSection}

            ## Runner log (trimmed)
            ```
            %{log}
            ```

            ## Hard requirements
            1. Keep package `%{packageName}` and class name `%{testClassName}`.
            2. Fix the root cause: wrong expectations, missing stubs, compilation errors. \
            Delete a test method only when the scenario it checks is genuinely impossible.
            3. Keep JUnit 5 + Mockito + AssertJ, the AAA structure and the naming convention.
            4. Use only APIs that appear in the sources above; the code must compile as-is.
            5. Preserve or update the "Business/TMS coverage" class-level Javadoc when external context is present.

            ## Output format
            Return EXACTLY ONE fenced Java code block with the complete corrected test class. \
            No explanations outside the code block.
            """;

    /** Follow-up sent when a model answered with something that is not a parsable Java class. */
    public static final String FORMAT_RETRY_PROMPT = """
            Your previous answer could not be used: %s
            Reply again with EXACTLY ONE fenced ```java code block containing the complete, syntactically \
            valid test class (package declaration, imports, class body). Do not add any text outside the code block.""";

    public String buildGenerationPrompt(JavaClassInfo classInfo) {
        return buildGenerationPrompt(classInfo, GenerationContext.empty());
    }

    public String buildGenerationPrompt(JavaClassInfo classInfo, ExternalTestContext externalContext) {
        return buildGenerationPrompt(classInfo, GenerationContext.of(externalContext));
    }

    public String buildGenerationPrompt(JavaClassInfo classInfo, GenerationContext context) {
        GenerationContext ctx = context == null ? GenerationContext.empty() : context;
        return GENERATION_TEMPLATE
                .replace("%{packageName}", classInfo.packageName())
                .replace("%{kind}", classInfo.kind().name().toLowerCase())
                .replace("%{className}", classInfo.className())
                .replace("%{springNote}", springNote(classInfo))
                .replace("%{javadocSection}", javadocSection(classInfo))
                .replace("%{methodList}", methodList(classInfo))
                .replace("%{dependencyList}", dependencyList(classInfo))
                .replace("%{relatedTypesSection}", relatedTypesSection(ctx, classInfo))
                .replace("%{externalContextSection}", externalContextSection(ctx.externalContext()))
                .replace("%{testClassName}", testClassName(classInfo))
                .replace("%{styleHint}", styleHint(classInfo))
                .replace("%{sourceCode}", sourceCode(classInfo));
    }

    public String buildFixPrompt(JavaClassInfo classInfo, GeneratedTestFile previousTest,
                                 ValidationResult validationResult) {
        return buildFixPrompt(classInfo, previousTest, validationResult, GenerationContext.empty());
    }

    public String buildFixPrompt(JavaClassInfo classInfo, GeneratedTestFile previousTest,
                                 ValidationResult validationResult, GenerationContext context) {
        GenerationContext ctx = context == null ? GenerationContext.empty() : context;
        return FIX_TEMPLATE
                .replace("%{failures}", failureList(validationResult))
                .replace("%{relatedTypesSection}", relatedTypesSection(ctx, classInfo))
                .replace("%{externalContextSection}", externalContextSection(ctx.externalContext()))
                .replace("%{log}", trimmedLog(validationResult))
                .replace("%{packageName}", previousTest.packageName())
                .replace("%{testClassName}", previousTest.className())
                .replace("%{previousTest}", previousTest.sourceCode())
                .replace("%{sourceCode}", sourceCode(classInfo));
    }

    public String buildFormatRetryPrompt(String reason) {
        return FORMAT_RETRY_PROMPT.formatted(reason == null || reason.isBlank() ? "no Java class was found" : reason);
    }

    public String testClassName(JavaClassInfo classInfo) {
        return classInfo.className() + "Test";
    }

    private String sourceCode(JavaClassInfo classInfo) {
        String source = classInfo.sourceCode() == null ? "" : classInfo.sourceCode();
        if (source.length() <= MAX_SOURCE_CHARS) {
            return source;
        }
        return source.substring(0, MAX_SOURCE_CHARS)
                + System.lineSeparator() + "// ... source truncated by AutoTestForge (" + source.length()
                + " chars); rely on the public API listed below for the rest ...";
    }

    private String springNote(JavaClassInfo classInfo) {
        return classInfo.isSpringComponent()
                ? " (Spring @" + classInfo.springStereotype() + ")"
                : "";
    }

    private String javadocSection(JavaClassInfo classInfo) {
        return classInfo.javadoc().isBlank()
                ? ""
                : "Javadoc: " + classInfo.javadoc() + System.lineSeparator();
    }

    private String methodList(JavaClassInfo classInfo) {
        return classInfo.publicMethods().stream()
                .map(this::describeMethod)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private String describeMethod(MethodInfo method) {
        String entry = "- " + method.signature();
        if (!method.javadoc().isBlank()) {
            entry += System.lineSeparator() + "  Javadoc: " + method.javadoc().replace("\n", " ").strip();
        }
        return entry;
    }

    private String dependencyList(JavaClassInfo classInfo) {
        if (classInfo.dependencies().isEmpty()) {
            return "(none - the class has no collaborators, do not use Mockito annotations)";
        }
        return classInfo.dependencies().stream()
                .map(this::describeDependency)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private String describeDependency(FieldDependency dependency) {
        return "- " + dependency.type() + " " + dependency.fieldName()
                + (dependency.injected() ? " (injected)" : "");
    }

    /**
     * Related project types: full source when small (records, interfaces, enums
     * are usually tiny and their exact shape matters), otherwise the public API.
     */
    private String relatedTypesSection(GenerationContext context, JavaClassInfo classInfo) {
        if (!context.hasRelatedTypes()) {
            return "";
        }
        StringBuilder section = new StringBuilder("""
                ## Related project types (collaborators and types used in the API)
                These are the real definitions from the same project. Stub collaborators and build fixtures \
                strictly according to them.
                """);
        for (JavaClassInfo related : context.relatedTypes()) {
            String entry = describeRelatedType(related);
            if (section.length() + entry.length() > MAX_RELATED_SECTION_CHARS) {
                section.append(System.lineSeparator()).append("(further related types omitted for brevity)");
                break;
            }
            section.append(System.lineSeparator()).append(entry);
        }
        return section.toString().stripTrailing();
    }

    private String describeRelatedType(JavaClassInfo related) {
        StringBuilder entry = new StringBuilder();
        entry.append("### ").append(related.kind().name().toLowerCase()).append(' ')
                .append(related.fullyQualifiedName());
        if (related.isSpringComponent()) {
            entry.append(" (@").append(related.springStereotype()).append(')');
        }
        entry.append(System.lineSeparator());
        String source = related.sourceCode() == null ? "" : related.sourceCode().strip();
        if (!source.isEmpty() && source.length() <= MAX_RELATED_TYPE_SOURCE_CHARS) {
            entry.append("```java").append(System.lineSeparator())
                    .append(source).append(System.lineSeparator())
                    .append("```");
            return entry.toString();
        }
        if (!related.javadoc().isBlank()) {
            entry.append("Javadoc: ").append(firstSentence(related.javadoc())).append(System.lineSeparator());
        }
        if (related.publicMethods().isEmpty()) {
            entry.append("(no public methods)");
        } else {
            entry.append("Public API:").append(System.lineSeparator());
            entry.append(related.publicMethods().stream()
                    .map(method -> "- " + method.signature())
                    .collect(Collectors.joining(System.lineSeparator())));
        }
        return entry.toString();
    }

    private String firstSentence(String javadoc) {
        String text = javadoc.replace("\n", " ").strip();
        int end = text.indexOf(". ");
        return end < 0 ? text : text.substring(0, end + 1);
    }

    private String externalContextSection(ExternalTestContext externalContext) {
        if (externalContext == null || externalContext.isEmpty()) {
            return """
                    ## External business and test-management context
                    (none configured or nothing found)
                    """.stripTrailing();
        }

        String snippets = externalContext.snippets().stream()
                .map(this::describeExternalContext)
                .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));
        return """
                ## External business and test-management context
                This may include Confluence pages, requirements, Zephyr/TMS XML exports or existing test cases. \
                Analyze it to choose meaningful business scenarios, expected outcomes, edge cases, test names \
                and coverage notes. Do not reference external IDs or systems from executable Java code unless \
                they are part of the class under test.
                %s
                """.formatted(trim(snippets, MAX_EXTERNAL_CONTEXT_CHARS)).stripTrailing();
    }

    private String describeExternalContext(ExternalContextSnippet snippet) {
        return """
                Source: %s
                Title: %s
                Content:
                %s
                """.formatted(snippet.source(), snippet.title(), snippet.content()).stripTrailing();
    }

    /** Controllers get MockMvc guidance; everything else stays a plain unit test. */
    private String styleHint(JavaClassInfo classInfo) {
        String stereotype = classInfo.springStereotype();
        if ("RestController".equals(stereotype) || "Controller".equals(stereotype)) {
            return "This is a Spring MVC controller: use MockMvcBuilders.standaloneSetup(...) with mocked "
                    + "collaborators. Do NOT use @SpringBootTest or @WebMvcTest (no application context is available).";
        }
        if (classInfo.publicMethods().stream().allMatch(MethodInfo::isStatic)) {
            return "All public methods are static: call them directly, do not instantiate the class and do not "
                    + "use Mockito. Do NOT use @SpringBootTest, @WebMvcTest or any Spring test context.";
        }
        return "Write a plain unit test. Do NOT use @SpringBootTest, @WebMvcTest or any Spring test context.";
    }

    private String failureList(ValidationResult validationResult) {
        if (validationResult.failures().isEmpty()) {
            return "(no structured failures - most likely a compilation error, see the log below)";
        }
        return validationResult.failures().stream()
                .map(TestFailure::describe)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private String trimmedLog(ValidationResult validationResult) {
        String log = validationResult.rawLog() == null ? "" : validationResult.rawLog();
        return trim(log, MAX_LOG_CHARS);
    }

    private String trim(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(value.length() - maxChars);
    }
}
