package com.autotestforge.web.mcp;

import com.autotestforge.web.api.JobResponse;
import com.autotestforge.web.api.StartGenerationRequest;
import com.autotestforge.web.job.GenerationJob;
import com.autotestforge.web.job.GenerationJobService;
import com.autotestforge.web.project.ProjectInspectionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal stateless MCP Streamable HTTP endpoint. It intentionally exposes a
 * small, high-value tool set so clients request compact context instead of
 * sending an entire repository to the model.
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final String JSON_RPC = "2.0";
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-03-26";

    private final ProjectInspectionService inspectionService;
    private final GenerationJobService jobService;
    private final ObjectMapper objectMapper;

    public McpController(ProjectInspectionService inspectionService,
                         GenerationJobService jobService,
                         ObjectMapper objectMapper) {
        this.inspectionService = inspectionService;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Object> handle(@RequestBody McpRequest request) {
        if (request.id() == null && request.method() != null && request.method().startsWith("notifications/")) {
            return ResponseEntity.accepted().build();
        }
        try {
            Object result = switch (request.method()) {
                case "initialize" -> initialize(request.params());
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", toolDefinitions());
                case "tools/call" -> callTool(request.params());
                default -> throw new McpProtocolException(-32601, "Method not found: " + request.method());
            };
            return ResponseEntity.ok(new McpResponse(JSON_RPC, request.id(), result, null));
        } catch (McpProtocolException e) {
            return ResponseEntity.ok(error(request.id(), e.code, e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(error(request.id(), -32602, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(error(request.id(), -32603,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private Map<String, Object> initialize(JsonNode params) {
        String protocolVersion = text(params, "protocolVersion", DEFAULT_PROTOCOL_VERSION);
        return Map.of(
                "protocolVersion", protocolVersion,
                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "serverInfo", Map.of("name", "AutoTestForge", "version", "0.1.0"));
    }

    private Map<String, Object> callTool(JsonNode params) {
        String name = requiredText(params, "name");
        JsonNode arguments = params == null ? null : params.path("arguments");
        Object value = switch (name) {
            case "inspect_project" -> inspectionService.inspect(requiredText(arguments, "projectPath"));
            case "get_class_context" -> inspectionService.classContext(
                    requiredText(arguments, "projectPath"),
                    requiredText(arguments, "className"),
                    bool(arguments, "includeSource", false),
                    integer(arguments, "maxSourceChars", 12_000));
            case "generate_tests" -> startGeneration(arguments);
            case "get_generation_job" -> getJob(arguments);
            default -> throw new McpProtocolException(-32602, "Unknown tool: " + name);
        };
        return toolResult(value, false);
    }

    private JobResponse startGeneration(JsonNode arguments) {
        StartGenerationRequest request = new StartGenerationRequest(
                requiredText(arguments, "projectPath"),
                stringList(arguments, "classes"),
                text(arguments, "provider", null),
                bool(arguments, "validate", true),
                bool(arguments, "dryRun", true),
                false, List.of(), null, List.of(), List.of());
        return JobResponse.from(jobService.start(request));
    }

    private JobResponse getJob(JsonNode arguments) {
        String id = requiredText(arguments, "jobId");
        GenerationJob job = jobService.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Generation job not found: " + id));
        return JobResponse.from(job);
    }

    private Map<String, Object> toolResult(Object value, boolean error) {
        try {
            return Map.of(
                    "content", List.of(Map.of("type", "text", "text", objectMapper.writeValueAsString(value))),
                    "isError", error);
        } catch (JsonProcessingException e) {
            throw new McpProtocolException(-32603, "Could not serialize tool result");
        }
    }

    private List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("inspect_project",
                "Return a compact list of Java classes and dependency counts. Call this before requesting source code.",
                schema(Map.of("projectPath", stringProperty("Server-side project directory")),
                        List.of("projectPath"))));
        tools.add(tool("get_class_context",
                "Return one class API, collaborators and dependency neighborhood; source is optional and bounded.",
                schema(Map.of(
                                "projectPath", stringProperty("Server-side project directory"),
                                "className", stringProperty("Simple or fully-qualified Java class name"),
                                "includeSource", booleanProperty("Include bounded source code; defaults to false"),
                                "maxSourceChars", integerProperty("Maximum source characters, capped at 50000")),
                        List.of("projectPath", "className"))));
        tools.add(tool("generate_tests",
                "Start an asynchronous agent run. dryRun defaults to true; pass false explicitly to write files.",
                schema(Map.of(
                                "projectPath", stringProperty("Server-side project directory"),
                                "classes", arrayProperty("Optional class names; empty means all testable classes"),
                                "provider", stringProperty("ollama, openai, compatible or offline"),
                                "validate", booleanProperty("Run and self-correct generated tests; defaults to true"),
                                "dryRun", booleanProperty("Do not write files; defaults to true")),
                        List.of("projectPath"))));
        tools.add(tool("get_generation_job",
                "Read progress and results of an asynchronous generation run.",
                schema(Map.of("jobId", stringProperty("Job id returned by generate_tests")), List.of("jobId"))));
        return tools;
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> schema) {
        return Map.of("name", name, "description", description, "inputSchema", schema);
    }

    private Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("properties", properties);
        result.put("required", required);
        result.put("additionalProperties", false);
        return result;
    }

    private Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private Map<String, Object> booleanProperty(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private Map<String, Object> integerProperty(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private Map<String, Object> arrayProperty(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }

    private List<String> stringList(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.get(field).forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText());
            }
        });
        return List.copyOf(values);
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || !node.get(field).isTextual()) {
            return fallback;
        }
        return node.get(field).asText();
    }

    private boolean bool(JsonNode node, String field, boolean fallback) {
        return node != null && node.has(field) && node.get(field).isBoolean()
                ? node.get(field).asBoolean() : fallback;
    }

    private int integer(JsonNode node, String field, int fallback) {
        return node != null && node.has(field) && node.get(field).canConvertToInt()
                ? node.get(field).asInt() : fallback;
    }

    private McpResponse error(JsonNode id, int code, String message) {
        return new McpResponse(JSON_RPC, id, null, new McpError(code, message));
    }

    public record McpRequest(String jsonrpc, JsonNode id, String method, JsonNode params) {
    }

    public record McpResponse(String jsonrpc, JsonNode id, Object result, McpError error) {
    }

    public record McpError(int code, String message) {
    }

    private static final class McpProtocolException extends RuntimeException {
        private final int code;

        private McpProtocolException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
