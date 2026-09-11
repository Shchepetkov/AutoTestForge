package com.autotestforge.ai;

import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.JavaClassInfo;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes LLM output conform to the conventions the pipeline relies on, even
 * when the model ignored the instructions:
 * <ul>
 *   <li>the package must equal the package of the class under test, otherwise
 *       the test lands in the wrong directory and loses package-private access;</li>
 *   <li>the class must be named {@code <ClassUnderTest>Test}, otherwise re-runs
 *       cannot detect the existing test and leave orphaned files behind.</li>
 * </ul>
 * Edits are textual and range-based so the model's formatting is preserved.
 */
public class GeneratedTestNormalizer {

    private static final Logger log = LoggerFactory.getLogger(GeneratedTestNormalizer.class);

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    public GeneratedTestFile normalize(GeneratedTestFile test, JavaClassInfo classUnderTest) {
        String expectedPackage = classUnderTest.packageName() == null ? "" : classUnderTest.packageName();
        String expectedClassName = classUnderTest.className() + "Test";
        String source = test.sourceCode();

        if (!expectedClassName.equals(test.className())) {
            log.info("Renaming generated test class {} to {}", test.className(), expectedClassName);
            source = test.className().equals(classUnderTest.className())
                    ? renameDeclarationOnly(source, test.className(), expectedClassName)
                    : renameType(source, test.className(), expectedClassName);
        }
        if (!expectedPackage.equals(test.packageName())) {
            log.info("Moving generated test from package '{}' to '{}'", test.packageName(), expectedPackage);
            source = replacePackage(source, expectedPackage);
        }
        if (source.equals(test.sourceCode())) {
            return test;
        }
        return new GeneratedTestFile(expectedPackage, expectedClassName, source);
    }

    private String renameType(String source, String oldName, String newName) {
        // Word-boundary replacement covers the declaration, constructors, logger lookups and self references.
        return Pattern.compile("\\b" + Pattern.quote(oldName) + "\\b").matcher(source).replaceAll(
                Matcher.quoteReplacement(newName));
    }

    /**
     * When the model reused the name of the class under test for the test class,
     * a global rename would also rewrite every reference to the real class, so
     * only the declaration identifier is touched.
     */
    private String renameDeclarationOnly(String source, String oldName, String newName) {
        Optional<Range> nameRange = parser.parse(source).getResult()
                .flatMap(unit -> unit.getTypes().stream()
                        .filter(type -> type.getNameAsString().equals(oldName))
                        .findFirst())
                .flatMap(type -> type.getName().getRange());
        if (nameRange.isEmpty()) {
            return source;
        }
        int[] offsets = offsets(source, nameRange.get());
        return source.substring(0, offsets[0]) + newName + source.substring(offsets[1]);
    }

    private String replacePackage(String source, String expectedPackage) {
        String declaration = expectedPackage.isBlank() ? "" : "package " + expectedPackage + ";";
        Optional<Range> existing = parser.parse(source).getResult()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .flatMap(PackageDeclaration::getRange);
        if (existing.isPresent()) {
            int[] offsets = offsets(source, existing.get());
            String replaced = source.substring(0, offsets[0]) + declaration + source.substring(offsets[1]);
            return declaration.isBlank() ? replaced.stripLeading() : replaced;
        }
        if (declaration.isBlank()) {
            return source;
        }
        Optional<Range> firstType = parser.parse(source).getResult()
                .filter(unit -> !unit.getTypes().isEmpty())
                .flatMap(unit -> unit.getImports().isEmpty()
                        ? unit.getTypes().stream().map(TypeDeclaration::getRange).flatMap(Optional::stream)
                        .findFirst()
                        : unit.getImports().get(0).getRange());
        int insertAt = firstType.map(range -> offsets(source, range)[0]).orElse(0);
        return source.substring(0, insertAt) + declaration + System.lineSeparator() + System.lineSeparator()
                + source.substring(insertAt);
    }

    /** Converts a JavaParser (1-based line/column) range into [start, end) character offsets. */
    private static int[] offsets(String source, Range range) {
        int start = offset(source, range.begin.line, range.begin.column);
        int end = offset(source, range.end.line, range.end.column) + 1;
        return new int[]{start, Math.min(end, source.length())};
    }

    private static int offset(String source, int line, int column) {
        int currentLine = 1;
        int index = 0;
        while (currentLine < line && index < source.length()) {
            if (source.charAt(index) == '\n') {
                currentLine++;
            }
            index++;
        }
        return Math.min(index + column - 1, source.length());
    }
}
