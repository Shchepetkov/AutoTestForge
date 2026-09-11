package com.autotestforge.ai;

import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.GenerationContext;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.LlmException;
import com.autotestforge.core.port.out.AiTestGeneratorPort;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AiTestGeneratorPort} adapter backed by a LangChain4j {@link ChatModel}
 * (Ollama, OpenAI, Anthropic, any OpenAI-compatible endpoint...).
 * <p>
 * Two independent retry loops make the adapter robust against the two ways an
 * LLM call goes wrong: transport failures are retried with exponential backoff,
 * and answers that do not contain a parsable Java class are sent back to the
 * model as a conversational follow-up asking for a corrected reply. The parsed
 * class is finally normalized to the expected package and name.
 */
public class LangChain4jTestGenerator implements AiTestGeneratorPort {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jTestGenerator.class);

    /** How many times a syntactically unusable answer is sent back for correction. */
    static final int MAX_FORMAT_RETRIES = 2;

    private final ChatModel chatModel;
    private final TestPromptBuilder promptBuilder;
    private final LlmResponseParser responseParser;
    private final GeneratedTestNormalizer normalizer;
    private final int maxRetries;
    private final long initialBackoffMillis;
    private final boolean useSystemMessage;

    public LangChain4jTestGenerator(ChatModel chatModel,
                                    TestPromptBuilder promptBuilder,
                                    LlmResponseParser responseParser,
                                    int maxRetries,
                                    long initialBackoffMillis) {
        this(chatModel, promptBuilder, responseParser, new GeneratedTestNormalizer(),
                maxRetries, initialBackoffMillis, true);
    }

    public LangChain4jTestGenerator(ChatModel chatModel,
                                    TestPromptBuilder promptBuilder,
                                    LlmResponseParser responseParser,
                                    GeneratedTestNormalizer normalizer,
                                    int maxRetries,
                                    long initialBackoffMillis,
                                    boolean useSystemMessage) {
        this.chatModel = chatModel;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
        this.normalizer = normalizer;
        this.maxRetries = Math.max(1, maxRetries);
        this.initialBackoffMillis = Math.max(0, initialBackoffMillis);
        this.useSystemMessage = useSystemMessage;
    }

    @Override
    public GeneratedTestFile generate(JavaClassInfo classInfo, String provider, GenerationContext context) {
        String prompt = promptBuilder.buildGenerationPrompt(classInfo, context);
        log.debug("Generation prompt for {} ({} chars)", classInfo.fullyQualifiedName(), prompt.length());
        return converse(prompt, classInfo);
    }

    @Override
    public GeneratedTestFile fix(JavaClassInfo classInfo, GeneratedTestFile previousTest,
                                 ValidationResult validationResult, String provider,
                                 GenerationContext context) {
        String prompt = promptBuilder.buildFixPrompt(classInfo, previousTest, validationResult, context);
        log.debug("Fix prompt for {} ({} chars)", previousTest.fullyQualifiedName(), prompt.length());
        return converse(prompt, classInfo);
    }

    /**
     * Sends the prompt and, when the answer is not usable Java, continues the
     * conversation with a correction request instead of failing the class.
     */
    private GeneratedTestFile converse(String prompt, JavaClassInfo classInfo) {
        String expectedClassName = promptBuilder.testClassName(classInfo);
        List<ChatMessage> messages = new ArrayList<>();
        if (useSystemMessage) {
            messages.add(SystemMessage.from(TestPromptBuilder.SYSTEM_PROMPT));
        }
        messages.add(UserMessage.from(prompt));

        LlmException lastParseError = null;
        for (int attempt = 0; attempt <= MAX_FORMAT_RETRIES; attempt++) {
            String response = chatWithRetry(messages, classInfo.fullyQualifiedName());
            try {
                GeneratedTestFile parsed = responseParser.parse(response, expectedClassName);
                return normalizer.normalize(parsed, classInfo);
            } catch (LlmException e) {
                lastParseError = e;
                log.warn("Unusable LLM answer for {} (format retry {}/{}): {}",
                        classInfo.fullyQualifiedName(), attempt + 1, MAX_FORMAT_RETRIES, e.getMessage());
                messages.add(AiMessage.from(response == null ? "" : response));
                messages.add(UserMessage.from(promptBuilder.buildFormatRetryPrompt(e.getMessage())));
            }
        }
        throw new LlmException("LLM did not return a usable test class for " + classInfo.fullyQualifiedName()
                + " after " + (MAX_FORMAT_RETRIES + 1) + " attempts: " + lastParseError.getMessage(), lastParseError);
    }

    private String chatWithRetry(List<ChatMessage> messages, String classFqn) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return chatModel.chat(messages).aiMessage().text();
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("LLM call failed for {} (attempt {}/{}): {}", classFqn, attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    sleep(initialBackoffMillis * (1L << (attempt - 1)));
                }
            }
        }
        throw new LlmException("LLM unreachable after " + maxRetries + " attempts for " + classFqn
                + ": " + (lastError == null ? "unknown error" : lastError.getMessage()), lastError);
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while waiting to retry the LLM call", e);
        }
    }
}
