package com.autotestforge.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/** Same configuration surface as the CLI, bound for the web application. */
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
