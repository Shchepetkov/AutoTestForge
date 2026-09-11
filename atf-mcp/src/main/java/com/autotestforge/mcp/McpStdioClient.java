package com.autotestforge.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal, long-lived stdio client for the Model Context Protocol.
 * <p>
 * Messages follow the MCP stdio transport: one JSON-RPC message per line,
 * delimited by {@code \n}. Servers that use LSP-style {@code Content-Length}
 * framing are detected and supported as well. A dedicated reader thread
 * dispatches responses to the pending requests, answers server-initiated
 * requests ({@code ping}, {@code roots/list}) and ignores notifications, so a
 * request that timed out never poisons later calls.
 */
public class McpStdioClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(McpStdioClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String CLIENT_NAME = "AutoTestForge";
    private static final String CLIENT_VERSION = "0.2.0";

    private final Process process;
    private final List<String> commandLine;
    private final Duration timeout;
    private final OutputStream stdin;
    private final Thread readerThread;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Object writeLock = new Object();
    private volatile boolean closed;
    private volatile String serverInfo = "unknown server";
    private volatile String negotiatedProtocolVersion = PROTOCOL_VERSION;

    public McpStdioClient(List<String> commandLine, Duration timeout) {
        if (commandLine == null || commandLine.isEmpty()) {
            throw new IllegalArgumentException("commandLine must not be empty");
        }
        this.commandLine = List.copyOf(commandLine);
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        try {
            this.process = new ProcessBuilder(this.commandLine).start();
        } catch (IOException e) {
            throw new McpToolException("Failed to start MCP server " + commandLine, e);
        }
        this.stdin = process.getOutputStream();
        drainStderr(process.getErrorStream());
        this.readerThread = new Thread(this::readLoop, "mcp-reader-" + process.pid());
        readerThread.setDaemon(true);
        readerThread.start();
        try {
            initialize();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    /** Human-readable {@code name version} of the server as reported during {@code initialize}. */
    public String serverInfo() {
        return serverInfo;
    }

    public String protocolVersion() {
        return negotiatedProtocolVersion;
    }

    public boolean isAlive() {
        return !closed && process.isAlive();
    }

    /** Names and descriptions of the tools the server exposes ({@code tools/list}). */
    public List<McpToolDescriptor> listTools() {
        List<McpToolDescriptor> tools = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> params = new LinkedHashMap<>();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", params).path("result");
            for (JsonNode tool : result.path("tools")) {
                tools.add(new McpToolDescriptor(
                        tool.path("name").asText(),
                        tool.path("description").asText(""),
                        tool.path("inputSchema")));
            }
            cursor = result.hasNonNull("nextCursor") ? result.path("nextCursor").asText() : null;
        } while (cursor != null && !cursor.isBlank());
        return tools;
    }

    /**
     * Calls a tool and returns its textual content (all {@code text} items joined,
     * non-text items and {@code structuredContent} rendered as JSON).
     *
     * @throws McpToolException on transport errors, timeouts or when the tool reports an error
     */
    public String callTool(String toolName, Map<String, ?> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        JsonNode result = request("tools/call", params).path("result");
        String content = extractContent(result);
        if (result.path("isError").asBoolean(false)) {
            throw new McpToolException("MCP tool '" + toolName + "' returned an error: " + content);
        }
        return content;
    }

    private void initialize() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of("roots", Map.of("listChanged", false)));
        params.put("clientInfo", Map.of("name", CLIENT_NAME, "version", CLIENT_VERSION));
        JsonNode result = request("initialize", params).path("result");
        if (result.hasNonNull("protocolVersion")) {
            negotiatedProtocolVersion = result.path("protocolVersion").asText();
        }
        JsonNode info = result.path("serverInfo");
        if (info.isObject()) {
            serverInfo = (info.path("name").asText("mcp-server") + " " + info.path("version").asText("")).strip();
        }
        write(notification("notifications/initialized", Map.of()));
        log.debug("MCP session established with {} (protocol {})", serverInfo, negotiatedProtocolVersion);
    }

    private JsonNode request(String method, Map<String, ?> params) {
        ensureOpen();
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            write(message(id, method, params));
            JsonNode response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.has("error")) {
                throw new McpToolException("MCP error for " + method + ": " + response.path("error"));
            }
            return response;
        } catch (TimeoutException e) {
            throw new McpToolException("Timed out after " + timeout + " waiting for MCP response to " + method
                    + " from " + serverInfo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException("Interrupted while waiting for MCP response to " + method, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw cause instanceof McpToolException mcp
                    ? mcp
                    : new McpToolException("MCP request " + method + " failed: " + cause.getMessage(), cause);
        } finally {
            pending.remove(id);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new McpToolException("MCP client for " + commandLine + " is closed");
        }
        if (!process.isAlive()) {
            throw new McpToolException("MCP server " + commandLine + " exited with code " + process.exitValue());
        }
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
            synchronized (writeLock) {
                stdin.write(body);
                stdin.write('\n');
                stdin.flush();
            }
        } catch (IOException e) {
            throw new McpToolException("Failed to write MCP message to " + commandLine, e);
        }
    }

    // ---------------------------------------------------------------- reader

    private void readLoop() {
        InputStream stdout = process.getInputStream();
        try {
            JsonNode message;
            while ((message = readMessage(stdout)) != null) {
                dispatch(message);
            }
        } catch (IOException e) {
            if (!closed) {
                log.debug("MCP stdout of {} closed: {}", commandLine, e.getMessage());
            }
        } finally {
            failPending(new McpToolException("MCP server " + commandLine + " closed the connection"
                    + (process.isAlive() ? "" : " (exit code " + process.exitValue() + ")")));
        }
    }

    private void dispatch(JsonNode message) {
        boolean hasId = message.hasNonNull("id");
        boolean hasMethod = message.hasNonNull("method");
        if (hasId && !hasMethod) {
            CompletableFuture<JsonNode> future = pending.remove(message.path("id").asLong());
            if (future != null) {
                future.complete(message);
            } else {
                log.debug("Dropping MCP response with unknown or expired id {}", message.path("id"));
            }
        } else if (hasId) {
            answerServerRequest(message);
        } else {
            log.debug("Ignoring MCP notification {}", message.path("method").asText());
        }
    }

    /** Servers may call back into the client; answer the ones we understand so they never block. */
    private void answerServerRequest(JsonNode request) {
        String method = request.path("method").asText();
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("jsonrpc", "2.0");
        reply.put("id", request.path("id").isNumber() ? request.path("id").asLong() : request.path("id").asText());
        switch (method) {
            case "ping" -> reply.put("result", Map.of());
            case "roots/list" -> reply.put("result", Map.of("roots", List.of()));
            default -> reply.put("error", Map.of("code", -32601, "message", "Method not supported by client: " + method));
        }
        try {
            write(reply);
        } catch (McpToolException e) {
            log.debug("Failed to answer MCP server request {}: {}", method, e.getMessage());
        }
    }

    /**
     * Reads the next JSON-RPC message. Newline-delimited JSON (MCP) is the
     * default; a line starting with {@code Content-Length:} switches to LSP
     * framing for that message. Non-JSON lines (stray logs) are skipped.
     */
    private JsonNode readMessage(InputStream input) throws IOException {
        String line;
        while ((line = readLine(input)) != null) {
            if (line.isBlank()) {
                continue;
            }
            if (isContentLengthHeader(line)) {
                return readContentLengthMessage(input, line);
            }
            JsonNode node = tryParse(line);
            if (node != null && node.isObject()) {
                return node;
            }
            log.debug("Ignoring non JSON-RPC stdout line from MCP server: {}", abbreviate(line));
        }
        return null;
    }

    private JsonNode readContentLengthMessage(InputStream input, String firstHeader) throws IOException {
        int contentLength = parseContentLength(firstHeader);
        String header;
        while ((header = readLine(input)) != null && !header.isEmpty()) {
            if (isContentLengthHeader(header)) {
                contentLength = parseContentLength(header);
            }
        }
        if (contentLength < 0) {
            throw new IOException("Invalid Content-Length framing from MCP server");
        }
        byte[] body = input.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new IOException("Unexpected end of MCP message body");
        }
        return MAPPER.readTree(body);
    }

    private static boolean isContentLengthHeader(String line) {
        return line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length());
    }

    private static int parseContentLength(String header) {
        try {
            return Integer.parseInt(header.substring(header.indexOf(':') + 1).strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static JsonNode tryParse(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                break;
            }
            buffer.write(value);
        }
        if (value == -1 && buffer.size() == 0) {
            return null;
        }
        String line = buffer.toString(StandardCharsets.UTF_8);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private String extractContent(JsonNode result) {
        JsonNode content = result.path("content");
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode item : content) {
                String type = item.path("type").asText();
                if ("text".equals(type)) {
                    text.append(item.path("text").asText()).append(System.lineSeparator());
                } else if ("resource".equals(type) && item.path("resource").hasNonNull("text")) {
                    text.append(item.path("resource").path("text").asText()).append(System.lineSeparator());
                } else {
                    text.append(item.toString()).append(System.lineSeparator());
                }
            }
        }
        if (text.isEmpty() && result.has("structuredContent")) {
            text.append(result.path("structuredContent").toString());
        }
        if (text.isEmpty() && !content.isArray()) {
            text.append(result.toString());
        }
        return text.toString().strip();
    }

    private void failPending(McpToolException error) {
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(error);
        }
        pending.clear();
    }

    private void drainStderr(InputStream stderr) {
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[mcp {}] {}", commandLine.get(0), line);
                }
            } catch (IOException ignored) {
                // stderr closes together with the process
            }
        }, "mcp-stderr-" + process.pid());
        drainer.setDaemon(true);
        drainer.start();
    }

    private static String abbreviate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
            // process is being terminated anyway
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            failPending(new McpToolException("MCP client closed"));
        }
    }

    /** One entry of {@code tools/list}. */
    public record McpToolDescriptor(String name, String description, JsonNode inputSchema) {

        public ObjectNode inputSchemaOrEmpty() {
            return inputSchema != null && inputSchema.isObject()
                    ? (ObjectNode) inputSchema
                    : MAPPER.createObjectNode();
        }
    }
}
