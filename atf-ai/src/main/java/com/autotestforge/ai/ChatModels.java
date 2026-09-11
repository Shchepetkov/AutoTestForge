package com.autotestforge.ai;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;

/** Factory for the LangChain4j chat models supported out of the box. */
public final class ChatModels {

    private ChatModels() {
    }

    public static ChatModel ollama(String baseUrl, String modelName, double temperature, Duration timeout) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(timeout)
                .build();
    }

    /**
     * OpenAI, or any OpenAI-compatible endpoint (Azure OpenAI, OpenRouter, Groq,
     * DeepSeek, Together, LM Studio, vLLM, llama.cpp server...) when
     * {@code baseUrl} is set.
     */
    public static ChatModel openAi(String baseUrl, String apiKey, String modelName, double temperature,
                                   Duration timeout) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey == null || apiKey.isBlank() ? "not-needed" : apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(timeout);
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    public static ChatModel openAi(String apiKey, String modelName, double temperature, Duration timeout) {
        return openAi(null, apiKey, modelName, temperature, timeout);
    }

    public static ChatModel anthropic(String baseUrl, String apiKey, String modelName, double temperature,
                                      int maxTokens, Duration timeout) {
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(timeout);
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }
}
