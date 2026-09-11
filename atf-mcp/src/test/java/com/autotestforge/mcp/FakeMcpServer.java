package com.autotestforge.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Minimal MCP server used by the client tests. Runs as a separate JVM.
 * <p>
 * Flags: {@code --lsp} switches the output to Content-Length framing,
 * {@code --noisy} writes a stray log line to stdout and sends a server-side
 * {@code ping} request plus a notification before answering tool calls.
 * Tools: {@code echo} (returns the {@code query} argument), {@code slow}
 * (sleeps 1.5s), {@code fail} (returns {@code isError}), {@code structured}
 * (returns only {@code structuredContent}).
 */
public final class FakeMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    private static final Object WRITE_LOCK = new Object();

    private FakeMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        Set<String> flags = Set.of(args);
        boolean lsp = flags.contains("--lsp");
        boolean noisy = flags.contains("--noisy");
        InputStream in = System.in;
        BufferedReader lineReader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = lineReader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode message = MAPPER.readTree(line);
            if (!message.has("method")) {
                continue;   // response to one of our own requests (e.g. ping)
            }
            if (!message.has("id")) {
                continue;   // notification
            }
            JsonNode request = message;
            Thread worker = new Thread(() -> handle(request, lsp, noisy));
            worker.start();
        }
    }

    private static void handle(JsonNode request, boolean lsp, boolean noisy) {
        long id = request.path("id").asLong();
        String method = request.path("method").asText();
        try {
            switch (method) {
                case "initialize" -> {
                    ObjectNode result = MAPPER.createObjectNode();
                    result.put("protocolVersion", request.path("params").path("protocolVersion").asText());
                    result.set("capabilities", MAPPER.createObjectNode().set("tools", MAPPER.createObjectNode()));
                    result.set("serverInfo", MAPPER.createObjectNode().put("name", "fake-mcp").put("version", "1.0"));
                    send(response(id, result), lsp);
                }
                case "tools/list" -> {
                    ObjectNode result = MAPPER.createObjectNode();
                    ArrayNode tools = result.putArray("tools");
                    for (String name : List.of("echo", "slow", "fail", "structured")) {
                        tools.addObject().put("name", name).put("description", name + " tool")
                                .set("inputSchema", MAPPER.createObjectNode().put("type", "object"));
                    }
                    send(response(id, result), lsp);
                }
                case "tools/call" -> {
                    if (noisy) {
                        OUT.println("[fake-mcp] stray log line on stdout");
                        send(MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 9_999)
                                .put("method", "ping"), lsp);
                        send(MAPPER.createObjectNode().put("jsonrpc", "2.0")
                                .put("method", "notifications/message")
                                .set("params", MAPPER.createObjectNode().put("level", "info").put("data", "hello")), lsp);
                    }
                    send(response(id, callTool(request.path("params"))), lsp);
                }
                default -> {
                    ObjectNode error = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", id);
                    error.set("error", MAPPER.createObjectNode().put("code", -32601).put("message", "unknown " + method));
                    send(error, lsp);
                }
            }
        } catch (Exception e) {
            System.err.println("fake server failure: " + e);
        }
    }

    private static ObjectNode callTool(JsonNode params) throws InterruptedException {
        String tool = params.path("name").asText();
        JsonNode arguments = params.path("arguments");
        ObjectNode result = MAPPER.createObjectNode();
        switch (tool) {
            case "echo" -> result.putArray("content").addObject().put("type", "text")
                    .put("text", "echo: " + arguments.path("query").asText());
            case "slow" -> {
                Thread.sleep(1_500);
                result.putArray("content").addObject().put("type", "text").put("text", "finally");
            }
            case "fail" -> {
                result.put("isError", true);
                result.putArray("content").addObject().put("type", "text").put("text", "tool exploded");
            }
            case "structured" -> {
                result.putArray("content");
                result.set("structuredContent", MAPPER.createObjectNode().put("answer", 42));
            }
            default -> {
                result.put("isError", true);
                result.putArray("content").addObject().put("type", "text").put("text", "unknown tool " + tool);
            }
        }
        return result;
    }

    private static ObjectNode response(long id, JsonNode result) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.set("result", result);
        return response;
    }

    private static void send(JsonNode message, boolean lsp) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(message);
        synchronized (WRITE_LOCK) {
            if (lsp) {
                OUT.print("Content-Length: " + body.length + "\r\n\r\n");
                OUT.write(body);
            } else {
                OUT.write(body);
                OUT.write('\n');
            }
            OUT.flush();
        }
    }
}
