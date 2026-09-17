package com.autotestforge.web.llm;

import com.autotestforge.ai.ChatModels;
import com.autotestforge.ai.LangChain4jTestGenerator;
import com.autotestforge.ai.LlmResponseParser;
import com.autotestforge.ai.OfflineTestGenerator;
import com.autotestforge.ai.TestPromptBuilder;
import com.autotestforge.core.port.out.AiTestGeneratorPort;
import com.autotestforge.web.config.AtfProperties;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves immutable settings for one job without changing the application's defaults. */
@Service
public class LlmConnectionService {
    private static final Set<String> PROVIDERS = Set.of("offline", "ollama", "openai", "compatible");
    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    private final AtfProperties properties;
    private final TestPromptBuilder promptBuilder;

    public LlmConnectionService(AtfProperties properties, TestPromptBuilder promptBuilder) {
        this.properties = properties;
        this.promptBuilder = promptBuilder;
    }

    public Configuration configuration() {
        Map<String, Defaults> providers = new LinkedHashMap<>();
        for (String provider : new String[]{"ollama", "compatible", "openai", "offline"}) {
            Settings settings = defaults(provider);
            providers.put(provider, new Defaults(safeUrl(settings.baseUrl()), settings.model(),
                    Math.toIntExact(settings.timeout().toSeconds()), settings.temperature(),
                    isConfigured(provider), !settings.apiKey().isBlank()));
        }
        return new Configuration(properties.llm().provider(), Map.copyOf(providers));
    }

    public Settings resolve(LlmConnectionRequest request, String legacyProvider, boolean requireModel) {
        String provider = first(request == null ? null : request.provider(),
                first(legacyProvider, properties.llm().provider())).toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("Select a supported LLM provider: ollama, compatible, openai or offline");
        }
        if (request == null && !isConfigured(provider)) {
            throw new IllegalArgumentException("The selected LLM provider is not configured. Set its connection in the website.");
        }
        Settings defaults = defaults(provider);
        if (provider.equals("offline")) {
            return defaults;
        }
        String baseUrl = normalizeUrl(first(request == null ? null : request.baseUrl(), defaults.baseUrl()));
        if (provider.equals("openai") && !baseUrl.equals(OPENAI_BASE_URL)) {
            throw new IllegalArgumentException("For a custom API URL select the compatible provider");
        }
        String model = first(request == null ? null : request.model(), defaults.model());
        if (requireModel && model.isBlank()) {
            throw new IllegalArgumentException("Model name is required");
        }
        if (model.length() > 256 || model.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Model name must be at most 256 characters with no control characters");
        }
        int timeoutSeconds = request != null && request.timeoutSeconds() != null
                ? request.timeoutSeconds() : Math.toIntExact(defaults.timeout().toSeconds());
        if (timeoutSeconds < 5 || timeoutSeconds > 1800) {
            throw new IllegalArgumentException("LLM timeout must be between 5 and 1800 seconds");
        }
        double temperature = request != null && request.temperature() != null
                ? request.temperature() : defaults.temperature();
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("Temperature must be between 0 and 2");
        }
        // Never send a configured secret to an endpoint supplied by a request.
        String apiKey = request == null ? "" : first(request.apiKey(), "");
        if (apiKey.isBlank() && !defaults.apiKey().isBlank() && baseUrl.equals(safeUrl(defaults.baseUrl()))) {
            apiKey = defaults.apiKey();
        }
        if (apiKey.length() > 8192 || apiKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("API key is invalid");
        }
        if (provider.equals("openai") && apiKey.isBlank()) {
            throw new IllegalArgumentException("An OpenAI API key is required");
        }
        return new Settings(provider, baseUrl, model, apiKey, Duration.ofSeconds(timeoutSeconds), temperature);
    }

    public AiTestGeneratorPort generator(Settings settings) {
        if (settings.provider().equals("offline")) {
            return new OfflineTestGenerator();
        }
        ChatModel model = switch (settings.provider()) {
            case "ollama" -> ChatModels.ollama(settings.baseUrl(), settings.model(), settings.apiKey(),
                    settings.temperature(), settings.timeout());
            case "openai" -> ChatModels.openAi(settings.apiKey(), settings.model(), settings.temperature(), settings.timeout());
            case "compatible" -> ChatModels.openAiCompatible(settings.baseUrl(), settings.apiKey(), settings.model(),
                    settings.temperature(), settings.timeout());
            default -> throw new IllegalArgumentException("Unsupported LLM provider");
        };
        return new LangChain4jTestGenerator(model, promptBuilder, new LlmResponseParser(),
                properties.llm().maxRetries(), properties.llm().retryBackoffMillis());
    }

    private boolean isConfigured(String provider) {
        return switch (provider) {
            case "offline" -> true;
            case "ollama" -> !defaults(provider).baseUrl().isBlank() && !defaults(provider).model().isBlank();
            case "openai" -> properties.llm().openai() != null && properties.llm().openai().isConfigured();
            case "compatible" -> properties.llm().compatible() != null && properties.llm().compatible().isConfigured();
            default -> false;
        };
    }

    private Settings defaults(String provider) {
        AtfProperties.Llm llm = properties.llm();
        return switch (provider) {
            case "ollama" -> new Settings(provider, llm.ollama().baseUrl(), llm.ollama().model(), "",
                    llm.ollama().timeout(), llm.temperature());
            case "openai" -> new Settings(provider, OPENAI_BASE_URL, llm.openai().model(),
                    first(llm.openai().apiKey(), ""), llm.openai().timeout(), llm.temperature());
            case "compatible" -> llm.compatible() == null
                    ? new Settings(provider, "http://localhost:8000/v1", "qwen2.5-coder", "", Duration.ofMinutes(5), llm.temperature())
                    : new Settings(provider, llm.compatible().baseUrl(), llm.compatible().model(),
                    first(llm.compatible().apiKey(), ""), llm.compatible().timeout(), llm.temperature());
            default -> new Settings("offline", "", "", "", Duration.ofMinutes(5), llm.temperature());
        };
    }

    static String normalizeUrl(String value) {
        try {
            URI uri = URI.create(value.strip());
            if (value.length() > 2048 || uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
            String normalized = uri.getScheme().toLowerCase(Locale.ROOT) + ":"
                    + uri.normalize().toASCIIString().substring(uri.getScheme().length() + 1);
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("LLM URL must be an http(s) base URL without credentials, query or fragment");
        }
    }

    private String safeUrl(String value) {
        try {
            return value == null || value.isBlank() ? "" : normalizeUrl(value);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    public record Settings(String provider, String baseUrl, String model, String apiKey,
                           Duration timeout, double temperature) {
        @Override
        public String toString() {
            return "LlmSettings[" + provider + ", credentials and connection details redacted]";
        }
    }

    public record Defaults(String baseUrl, String model, int timeoutSeconds, double temperature,
                           boolean configured, boolean apiKeyConfigured) { }

    public record Configuration(String defaultProvider, Map<String, Defaults> providers) { }
}
