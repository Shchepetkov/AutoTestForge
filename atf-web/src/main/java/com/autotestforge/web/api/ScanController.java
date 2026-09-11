package com.autotestforge.web.api;

import com.autotestforge.web.job.GenerationJobService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Synchronous scan preview: which classes would a run with these filters process? No LLM calls. */
@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private final GenerationJobService jobService;

    public ScanController(GenerationJobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ScanResponse scan(@Valid @RequestBody ScanRequest request) {
        return ScanResponse.from(jobService.scan(request));
    }
}
