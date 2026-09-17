package com.autotestforge.web.llm;

/** Optional per-run connection settings. Credentials are never persisted or echoed. */
public record LlmConnectionRequest(String provider, String baseUrl, String model, String apiKey,
                                   Integer timeoutSeconds, Double temperature) {
    @Override
    public String toString() {
        return "LlmConnectionRequest[credentials and connection details redacted]";
    }
}
