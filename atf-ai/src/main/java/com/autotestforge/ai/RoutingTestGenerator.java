package com.autotestforge.ai;

import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.GenerationContext;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.LlmException;
import com.autotestforge.core.port.out.AiTestGeneratorPort;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Routes generation requests to a concrete provider ("ollama", "openai",
 * "anthropic", "offline", ...). The provider can be overridden per request
 * (CLI flag {@code --llm}, web form field); otherwise the configured default
 * is used.
 */
public class RoutingTestGenerator implements AiTestGeneratorPort {

    private final Map<String, AiTestGeneratorPort> delegates;
    private final String defaultProvider;

    public RoutingTestGenerator(Map<String, AiTestGeneratorPort> delegates, String defaultProvider) {
        this.delegates = new TreeMap<>();
        delegates.forEach((name, delegate) -> this.delegates.put(name.toLowerCase(), delegate));
        this.defaultProvider = defaultProvider == null ? "" : defaultProvider.toLowerCase();
        if (!this.delegates.containsKey(this.defaultProvider)) {
            throw new IllegalArgumentException("Default LLM provider '" + defaultProvider
                    + "' is not configured. Configured providers: " + this.delegates.keySet());
        }
    }

    /** Names of the providers that can be selected per run. */
    public Set<String> providers() {
        return Set.copyOf(delegates.keySet());
    }

    public String defaultProvider() {
        return defaultProvider;
    }

    @Override
    public GeneratedTestFile generate(JavaClassInfo classInfo, String provider, GenerationContext context) {
        return resolve(provider).generate(classInfo, provider, context);
    }

    @Override
    public GeneratedTestFile fix(JavaClassInfo classInfo, GeneratedTestFile previousTest,
                                 ValidationResult validationResult, String provider,
                                 GenerationContext context) {
        return resolve(provider).fix(classInfo, previousTest, validationResult, provider, context);
    }

    private AiTestGeneratorPort resolve(String provider) {
        String key = provider == null || provider.isBlank() ? defaultProvider : provider.strip().toLowerCase();
        AiTestGeneratorPort delegate = delegates.get(key);
        if (delegate == null) {
            throw new LlmException("Unknown or unconfigured LLM provider '" + key
                    + "'. Configured providers: " + delegates.keySet());
        }
        return delegate;
    }
}
