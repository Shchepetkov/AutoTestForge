package com.autotestforge.web.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Small, bounded, source-free requests to discover models and test a connection. */
@Service
public class LlmDiagnosticsService {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final LlmConnectionService connections;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public LlmDiagnosticsService(LlmConnectionService connections, ObjectMapper mapper) {
        this.connections = connections;
        this.mapper = mapper;
    }

    public List<String> models(LlmConnectionRequest request) {
        LlmConnectionService.Settings settings = connections.resolve(request, null, false);
        if (settings.provider().equals("offline")) {
            return List.of();
        }
        boolean ollama = settings.provider().equals("ollama");
        JsonNode response = exchange(settings, ollama ? "/api/tags" : "/models", null);
        JsonNode items = response.path(ollama ? "models" : "data");
        if (!items.isArray()) {
            throw new ConnectionException("The server did not return a model list. Check the provider and API base URL.");
        }
        List<String> models = new ArrayList<>();
        for (JsonNode item : items) {
            String model = item.path(ollama ? "name" : "id").asText("");
            if (!model.isBlank() && model.length() <= 256 && model.chars().noneMatch(Character::isISOControl)) {
                models.add(model);
            }
            if (models.size() >= 1000) {
                break;
            }
        }
        return models.stream().distinct().sorted().toList();
    }

    public TestResult test(LlmConnectionRequest request) {
        LlmConnectionService.Settings settings = connections.resolve(request, null, true);
        if (settings.provider().equals("offline")) {
            return new TestResult(true, "Offline mode is ready. No LLM connection is needed.");
        }
        boolean ollama = settings.provider().equals("ollama");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model());
        body.put("messages", List.of(Map.of("role", "user", "content", "Reply with the single word OK.")));
        body.put("stream", false);
        if (ollama) {
            body.put("options", Map.of("num_predict", 64, "temperature", settings.temperature()));
        } else {
            body.put("max_tokens", 64);
            body.put("temperature", settings.temperature());
        }
        try {
            JsonNode response = exchange(settings, ollama ? "/api/chat" : "/chat/completions", body);
            JsonNode message = ollama ? response.path("message") : response.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            String reasoning = message.path(ollama ? "thinking" : "reasoning_content").asText("");
            if (content.isBlank() && reasoning.isBlank()) {
                return new TestResult(false, "The server returned no chat text. Check that the selected model supports chat completion.");
            }
            return new TestResult(true, content.isBlank()
                    ? "The model responded with reasoning. The short connection check ended before its final answer."
                    : "Connection successful: the model answered the test message.");
        } catch (ConnectionException e) {
            return new TestResult(false, e.getMessage());
        }
    }

    private JsonNode exchange(LlmConnectionService.Settings settings, String path, Map<String, Object> body) {
        Duration timeout = Duration.ofSeconds(Math.min(settings.timeout().toSeconds(), 60));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(settings.baseUrl() + path))
                .timeout(timeout).header("Accept", "application/json");
        if (!settings.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + settings.apiKey());
        }
        if (body != null) {
            try {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            } catch (JsonProcessingException e) {
                throw new ConnectionException("Could not prepare the connection check");
            }
        }
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(builder.build(), ignored -> new BoundedBody());
        try {
            HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ConnectionException(statusMessage(response.statusCode()));
            }
            JsonNode json = mapper.readTree(response.body());
            if (json == null || !json.isObject()) {
                throw new ConnectionException("The server returned no JSON object. Check the provider and API base URL.");
            }
            return json;
        } catch (TimeoutException | HttpTimeoutException e) {
            pending.cancel(true);
            throw new ConnectionException("LLM connection timed out. Check the address, VPN and whether the model is loaded; connection checks allow up to 60 seconds.");
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new ConnectionException("LLM connection check was interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof ConnectionException connectionException) {
                throw connectionException;
            }
            if (cause instanceof HttpTimeoutException) {
                throw new ConnectionException("LLM connection timed out. Check the address, VPN and whether the model is loaded.");
            }
            if (cause instanceof SSLException || hasCause(e, SSLException.class)) {
                throw new ConnectionException("TLS certificate verification failed. Install your company's trusted CA certificate in Java.");
            }
            throw new ConnectionException(cause instanceof ConnectException
                    ? "Cannot connect to the LLM server. Check its address, port, VPN and that the service is running."
                    : "Cannot reach the LLM API. Check the address, VPN, DNS and server availability.");
        } catch (java.io.IOException e) {
            throw new ConnectionException("The server returned invalid JSON. Check the provider and API base URL.");
        }
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private String statusMessage(int status) {
        return switch (status) {
            case 401, 403 -> "LLM authorization failed (HTTP " + status + "). Check the API key and model permissions.";
            case 404 -> "LLM API or model was not found (HTTP 404). Check the model and base URL; compatible APIs usually end in /v1.";
            case 400, 422 -> "The LLM rejected the chat request (HTTP " + status + "). Check the model, provider and supported settings.";
            case 429 -> "The LLM server is rate-limited or its quota is exhausted (HTTP 429). Try again later.";
            default -> "LLM server returned HTTP " + status + ". Check the API address and server availability.";
        };
    }

    public record TestResult(boolean success, String message) { }

    /** Contains only an application-authored message, never a remote response body or credential. */
    public static class ConnectionException extends RuntimeException {
        public ConnectionException(String message) {
            super(message);
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new ConnectionException("The LLM diagnostic response exceeded 1 MB."));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
