package com.autotestforge.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Minimal stdio MCP client for one-shot tool calls. */
public class McpStdioClient implements Closeable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Process process;
    private final Duration timeout;
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private long nextId = 1;

    public McpStdioClient(List<String> commandLine, Duration timeout) {
        if (commandLine == null || commandLine.isEmpty()) {
            throw new IllegalArgumentException("commandLine must not be empty");
        }
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        try {
            this.process = new ProcessBuilder(commandLine).start();
            drain(process.getErrorStream());
            initialize();
        } catch (IOException e) {
            throw new McpToolException("Failed to start MCP server " + commandLine, e);
        }
    }

    public String callTool(String toolName, Map<String, ?> arguments) {
        long id = nextId++;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        write(message(id, "tools/call", params));
        JsonNode response = readResponse(id);
        failIfError(response);
        JsonNode result = response.path("result");
        String content = extractContent(result);
        if (result.path("isError").asBoolean(false)) {
            throw new McpToolException("MCP tool returned an error: " + content);
        }
        return content;
    }

    private void initialize() {
        long id = nextId++;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "AutoTestForge", "version", "0.1.0"));
        write(message(id, "initialize", params));
        JsonNode response = readResponse(id);
        failIfError(response);
        write(notification("notifications/initialized", Map.of()));
    }

    private Map<String, Object> message(long id, String method, Map<String, ?> params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        return message;
    }

    private Map<String, Object> notification(String method, Map<String, ?> params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params);
        return message;
    }

    private void write(Map<String, Object> message) {
        try {
            byte[] body = MAPPER.writeValueAsBytes(message);
            String header = "Content-Length: " + body.length + "\r\n\r\n";
            OutputStream output = process.getOutputStream();
            output.write(header.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        } catch (IOException e) {
            throw new McpToolException("Failed to write MCP message", e);
        }
    }

    private JsonNode readResponse(long id) {
        CompletableFuture<JsonNode> future = CompletableFuture.supplyAsync(() -> readResponseBlocking(id), ioExecutor);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            throw new McpToolException("Timed out waiting for MCP response id " + id, e);
        }
    }

    private JsonNode readResponseBlocking(long id) {
        try {
            while (true) {
                JsonNode message = readMessage(process.getInputStream());
                if (message.has("id") && message.path("id").asLong() == id) {
                    return message;
                }
            }
        } catch (IOException e) {
            throw new McpToolException("Failed to read MCP response id " + id, e);
        }
    }

    private JsonNode readMessage(InputStream input) throws IOException {
        int contentLength = -1;
        String line;
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0 && "content-length".equalsIgnoreCase(line.substring(0, separator).strip())) {
                contentLength = Integer.parseInt(line.substring(separator + 1).strip());
            }
        }
        if (contentLength < 0) {
            throw new IOException("MCP response did not include Content-Length");
        }
        byte[] body = input.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new IOException("Unexpected end of MCP response");
        }
        return MAPPER.readTree(body);
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                buffer.write(value);
            }
        }
        if (value == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private void failIfError(JsonNode response) {
        if (response.has("error")) {
            throw new McpToolException("MCP error: " + response.path("error"));
        }
    }

    private String extractContent(JsonNode result) {
        JsonNode content = result.path("content");
        if (!content.isArray()) {
            return result.toString();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode item : content) {
            if ("text".equals(item.path("type").asText())) {
                text.append(item.path("text").asText()).append(System.lineSeparator());
            } else {
                text.append(item.toString()).append(System.lineSeparator());
            }
        }
        return text.toString().strip();
    }

    private void drain(InputStream input) {
        CompletableFuture.runAsync(() -> {
            try {
                input.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // Process shutdown closes stderr; no action needed.
            }
        }, ioExecutor);
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            ioExecutor.shutdownNow();
        }
    }
}
