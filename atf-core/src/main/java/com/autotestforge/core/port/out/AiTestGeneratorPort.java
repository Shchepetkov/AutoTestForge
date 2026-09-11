package com.autotestforge.core.port.out;

import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.GenerationContext;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.LlmException;

/** Driven port: turns class metadata into a compilable test class using an LLM. */
public interface AiTestGeneratorPort {

    /**
     * Generates a brand-new test class for {@code classInfo}.
     *
     * @param provider provider override ("ollama", "openai", "offline") or null for the configured default
     * @throws LlmException when the model is unreachable or returns unusable output
     */
    default GeneratedTestFile generate(JavaClassInfo classInfo, String provider) {
        return generate(classInfo, provider, GenerationContext.empty());
    }

    GeneratedTestFile generate(JavaClassInfo classInfo, String provider, GenerationContext context);

    /**
     * Self-correction round: asks the LLM to repair {@code previousTest} given
     * the validation failures.
     */
    default GeneratedTestFile fix(JavaClassInfo classInfo, GeneratedTestFile previousTest,
                                  ValidationResult validationResult, String provider) {
        return fix(classInfo, previousTest, validationResult, provider, GenerationContext.empty());
    }

    GeneratedTestFile fix(JavaClassInfo classInfo, GeneratedTestFile previousTest,
                          ValidationResult validationResult, String provider, GenerationContext context);
}
