package com.autotestforge.cli.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Application configuration bound from {@code application.yml} / environment.
 * See the README for every knob and its default.
 */
@ConfigurationProperties(prefix = "atf")
public record AtfProperties(Llm llm, Validation validation) {

    public record Llm(
            String provider,
            double temperature,
            int maxRetries,
            long retryBackoffMillis,
            Ollama ollama,
            OpenAi openai) {

        public record Ollama(String baseUrl, String model, Duration timeout) {
        }

        public record OpenAi(String apiKey, String model, Duration timeout) {

            public boolean isConfigured() {
                return apiKey != null && !apiKey.isBlank();
            }
        }
    }

    public record Validation(
            int maxFixAttempts,
            boolean preferDocker,
            String mavenImage,
            String gradleImage,
            Duration timeout,
            Path cacheDir) {
    }
}
