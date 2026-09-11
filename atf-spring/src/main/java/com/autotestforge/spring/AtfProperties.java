package com.autotestforge.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Application configuration bound from {@code application.yml}, environment
 * variables or command-line flags ({@code --atf.llm.provider=...}). Defaults
 * live in {@code atf-defaults.properties} and are documented in the README.
 */
@ConfigurationProperties(prefix = "atf")
public record AtfProperties(Llm llm, Generation generation, Validation validation, Context context) {

    public AtfProperties {
        generation = generation == null ? new Generation(1, false) : generation;
        context = context == null ? new Context(false, List.of()) : context;
    }

    public record Llm(
            String provider,
            double temperature,
            int maxRetries,
            long retryBackoffMillis,
            Ollama ollama,
            OpenAi openai,
            Anthropic anthropic) {

        public record Ollama(String baseUrl, String model, Duration timeout) {
        }

        /**
         * OpenAI or any OpenAI-compatible endpoint. Registered when an API key is
         * present or when a custom base URL points at a local server that needs none.
         */
        public record OpenAi(String apiKey, String baseUrl, String model, Duration timeout) {

            public boolean isConfigured() {
                return hasText(apiKey) || hasText(baseUrl);
            }
        }

        public record Anthropic(String apiKey, String baseUrl, String model, int maxTokens, Duration timeout) {

            public boolean isConfigured() {
                return hasText(apiKey);
            }
        }
    }

    /**
     * @param parallelism       classes processed concurrently by default (1..16)
     * @param overwriteExisting regenerate classes that already have a test by default
     */
    public record Generation(int parallelism, boolean overwriteExisting) {
    }

    public record Validation(
            int maxFixAttempts,
            boolean preferDocker,
            String mavenImage,
            String gradleImage,
            Duration timeout,
            Path cacheDir) {
    }

    public record Context(
            boolean enabled,
            List<ContextSource> sources) {

        public Context {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    public record ContextSource(
            String name,
            boolean enabled,
            String command,
            List<String> args,
            String toolName,
            String queryArgument,
            String queryTemplate,
            Map<String, String> arguments,
            Duration timeout,
            int maxChars) {
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
