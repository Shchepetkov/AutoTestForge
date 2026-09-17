package com.autotestforge.ai;

import com.autotestforge.core.domain.ExternalTestContext;
import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.LlmException;
import com.autotestforge.core.port.out.AiTestGeneratorPort;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AiTestGeneratorPort} adapter backed by a LangChain4j {@link ChatModel}
 * (Ollama, OpenAI, ...). Calls are retried with exponential backoff because
 * LLM endpoints fail transiently.
 */
public class LangChain4jTestGenerator implements AiTestGeneratorPort {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jTestGenerator.class);

    private final ChatModel chatModel;
    private final TestPromptBuilder promptBuilder;
    private final LlmResponseParser responseParser;
    private final int maxRetries;
    private final long initialBackoffMillis;

    public LangChain4jTestGenerator(ChatModel chatModel,
                                    TestPromptBuilder promptBuilder,
                                    LlmResponseParser responseParser,
                                    int maxRetries,
                                    long initialBackoffMillis) {
        this.chatModel = chatModel;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
        this.maxRetries = Math.min(10, Math.max(1, maxRetries));
        this.initialBackoffMillis = Math.min(60_000, Math.max(0, initialBackoffMillis));
    }

    @Override
    public GeneratedTestFile generate(JavaClassInfo classInfo, String provider, ExternalTestContext externalContext) {
        String prompt = promptBuilder.buildGenerationPrompt(classInfo, externalContext);
        log.debug("Generation prompt for {} ({} chars)", classInfo.fullyQualifiedName(), prompt.length());
        return chatWithRetry(prompt, classInfo.fullyQualifiedName());
    }

    @Override
    public GeneratedTestFile fix(JavaClassInfo classInfo, GeneratedTestFile previousTest,
                                 ValidationResult validationResult, String provider,
                                 ExternalTestContext externalContext) {
        String prompt = promptBuilder.buildFixPrompt(classInfo, previousTest, validationResult, externalContext);
        log.debug("Fix prompt for {} ({} chars)", previousTest.fullyQualifiedName(), prompt.length());
        return chatWithRetry(prompt, classInfo.fullyQualifiedName());
    }

    private GeneratedTestFile chatWithRetry(String prompt, String classFqn) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return responseParser.parse(chatModel.chat(prompt));
            } catch (RuntimeException e) {
                lastError = e;
                // Upstream exception messages may echo Authorization headers, prompts or raw server responses.
                log.warn("LLM call failed for {} (attempt {}/{}): {}", classFqn, attempt, maxRetries, e.getClass().getSimpleName());
                if (Thread.currentThread().isInterrupted()) {
                    throw new LlmException("LLM call was interrupted");
                }
                if (attempt < maxRetries) {
                    sleep(Math.min(60_000, initialBackoffMillis * (1L << (attempt - 1))));
                }
            }
        }
        throw new LlmException("LLM generation failed after " + maxRetries + " attempts for " + classFqn
                + " (" + lastError.getClass().getSimpleName() + "). Check the connection, model and its Java output.");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while waiting to retry the LLM call", e);
        }
    }
}
