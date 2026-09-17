package com.autotestforge.ai;

import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.exception.LlmException;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the generated test class from raw LLM output: finds fenced code
 * blocks, validates the candidate with JavaParser (the response must be real,
 * syntactically correct Java) and reads the package and class name from the AST.
 */
public class LlmResponseParser {

    private static final int MAX_RESPONSE_CHARS = 1_000_000;
    private static final Pattern CODE_BLOCK = Pattern.compile("```(?:java)?[ \\t]*\\R(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern THINK_BLOCK = Pattern.compile("\\A\\s*<think>.*?</think>\\s*", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * @throws LlmException when no syntactically valid Java class is found in the response
     */
    public GeneratedTestFile parse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            throw new LlmException("LLM returned an empty response");
        }
        if (llmResponse.length() > MAX_RESPONSE_CHARS) {
            throw new LlmException("LLM response exceeds the maximum supported size");
        }
        String answer = llmResponse.strip();
        while (THINK_BLOCK.matcher(answer).find()) {
            answer = THINK_BLOCK.matcher(answer).replaceFirst("");
        }
        if (answer.regionMatches(true, 0, "<think>", 0, 7)) {
            throw new LlmException("LLM returned incomplete reasoning without a final Java answer");
        }
        for (String candidate : candidates(answer)) {
            Optional<GeneratedTestFile> parsed = tryParse(candidate);
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        throw new LlmException("LLM response does not contain a syntactically valid Java class");
    }

    private List<String> candidates(String llmResponse) {
        List<String> candidates = new ArrayList<>();
        Matcher matcher = CODE_BLOCK.matcher(llmResponse);
        while (matcher.find()) {
            candidates.add(matcher.group(1).strip());
        }
        // Some models return bare code without fences.
        if (candidates.isEmpty()) {
            candidates.add(llmResponse.strip());
        }
        return candidates;
    }

    private Optional<GeneratedTestFile> tryParse(String source) {
        // JavaParser keeps mutable parsing state; jobs may parse responses concurrently.
        JavaParser parser = new JavaParser(
                new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        ParseResult<CompilationUnit> result = parser.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return Optional.empty();
        }
        CompilationUnit unit = result.getResult().get();
        if (unit.getTypes().isEmpty()) {
            return Optional.empty();
        }
        TypeDeclaration<?> primaryType = unit.getTypes().stream().filter(TypeDeclaration::isPublic)
                .findFirst().orElse(unit.getType(0));
        String packageName = unit.getPackageDeclaration()
                .map(pkg -> pkg.getNameAsString())
                .orElse("");
        return Optional.of(new GeneratedTestFile(packageName, primaryType.getNameAsString(), source));
    }

}
