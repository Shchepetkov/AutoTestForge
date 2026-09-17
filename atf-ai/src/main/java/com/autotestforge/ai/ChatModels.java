package com.autotestforge.ai;

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

    public static ChatModel openAi(String apiKey, String modelName, double temperature, Duration timeout) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(timeout)
                .build();
    }

    /**
     * Connects to any OpenAI-compatible chat-completions endpoint. This covers
     * self-hosted vLLM, LM Studio, LocalAI and hosted providers that expose the
     * same protocol (including Qwen-compatible gateways).
     */
    public static ChatModel openAiCompatible(String baseUrl, String apiKey, String modelName,
                                             double temperature, Duration timeout) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey == null || apiKey.isBlank() ? "not-required" : apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(timeout)
                .build();
    }
}
