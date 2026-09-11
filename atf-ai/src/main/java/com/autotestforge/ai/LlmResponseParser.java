package com.autotestforge.ai;

import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.exception.LlmException;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the generated test class from raw LLM output: finds fenced code
 * blocks, validates each candidate with JavaParser (the response must be real,
 * syntactically correct Java) and reads the package and class name from the AST.
 * When several blocks parse (models like to echo the class under test first),
 * the one that looks like a test class is preferred.
 */
public class LlmResponseParser {

    private static final Pattern CODE_BLOCK = Pattern.compile("```(?:[a-zA-Z]+)?\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern UNTERMINATED_CODE_BLOCK = Pattern.compile("```(?:[a-zA-Z]+)?\\s*\\n(.*)$", Pattern.DOTALL);

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    /**
     * @throws LlmException when no syntactically valid Java class is found in the response
     */
    public GeneratedTestFile parse(String llmResponse) {
        return parse(llmResponse, null);
    }

    /**
     * @param expectedClassName preferred simple name of the test class; used to rank candidate blocks
     * @throws LlmException when no syntactically valid Java class is found in the response
     */
    public GeneratedTestFile parse(String llmResponse, String expectedClassName) {
        if (llmResponse == null || llmResponse.isBlank()) {
            throw new LlmException("LLM returned an empty response");
        }
        List<Candidate> parsed = new ArrayList<>();
        for (String candidate : candidates(llmResponse)) {
            tryParse(candidate).ifPresent(parsed::add);
        }
        if (parsed.isEmpty()) {
            throw new LlmException("LLM response does not contain a syntactically valid Java class. "
                    + "Response starts with: " + preview(llmResponse));
        }
        return parsed.stream()
                .max(Comparator.comparingInt(candidate -> candidate.score(expectedClassName)))
                .orElseThrow()
                .file();
    }

    private List<String> candidates(String llmResponse) {
        List<String> candidates = new ArrayList<>();
        Matcher matcher = CODE_BLOCK.matcher(llmResponse);
        int lastEnd = 0;
        while (matcher.find()) {
            candidates.add(matcher.group(1).strip());
            lastEnd = matcher.end();
        }
        // Truncated answers sometimes lose the closing fence: try the tail after the last complete block.
        Matcher unterminated = UNTERMINATED_CODE_BLOCK.matcher(llmResponse.substring(lastEnd));
        if (unterminated.find()) {
            candidates.add(unterminated.group(1).strip());
        }
        // Some models return bare code without fences.
        if (candidates.isEmpty()) {
            candidates.add(llmResponse.strip());
        }
        return candidates;
    }

    private Optional<Candidate> tryParse(String source) {
        ParseResult<CompilationUnit> result = parser.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return Optional.empty();
        }
        CompilationUnit unit = result.getResult().get();
        if (unit.getTypes().isEmpty()) {
            return Optional.empty();
        }
        TypeDeclaration<?> primaryType = unit.getTypes().stream()
                .filter(type -> type.isPublic())
                .findFirst()
                .orElse(unit.getType(0));
        String packageName = unit.getPackageDeclaration()
                .map(pkg -> pkg.getNameAsString())
                .orElse("");
        boolean hasTests = source.contains("@Test") || source.contains("org.junit");
        return Optional.of(new Candidate(
                new GeneratedTestFile(packageName, primaryType.getNameAsString(), source), hasTests));
    }

    private String preview(String response) {
        String stripped = response.strip();
        return stripped.substring(0, Math.min(120, stripped.length()));
    }

    private record Candidate(GeneratedTestFile file, boolean hasTests) {

        int score(String expectedClassName) {
            int score = 0;
            if (hasTests) {
                score += 4;
            }
            if (expectedClassName != null && expectedClassName.equals(file.className())) {
                score += 2;
            }
            if (file.className().endsWith("Test") || file.className().endsWith("Tests")
                    || file.className().endsWith("IT")) {
                score += 1;
            }
            return score;
        }
    }
}
