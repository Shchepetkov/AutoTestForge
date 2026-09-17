package com.autotestforge.web.api;

import com.autotestforge.web.llm.LlmConnectionRequest;
import com.autotestforge.web.llm.LlmConnectionService;
import com.autotestforge.web.llm.LlmDiagnosticsService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/llm")
public class LlmController {
    private final LlmConnectionService connections;
    private final LlmDiagnosticsService diagnostics;

    public LlmController(LlmConnectionService connections, LlmDiagnosticsService diagnostics) {
        this.connections = connections;
        this.diagnostics = diagnostics;
    }

    @GetMapping("/config")
    public ResponseEntity<LlmConnectionService.Configuration> configuration() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(connections.configuration());
    }

    @PostMapping("/models")
    public Map<String, List<String>> models(@RequestBody LlmConnectionRequest request) {
        return Map.of("models", diagnostics.models(request));
    }

    @PostMapping("/test")
    public LlmDiagnosticsService.TestResult test(@RequestBody LlmConnectionRequest request) {
        return diagnostics.test(request);
    }
}
