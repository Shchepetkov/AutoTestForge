package com.autotestforge.web.api;

import com.autotestforge.web.job.GenerationJob;
import com.autotestforge.web.job.GenerationJobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST API for starting generation runs and polling their progress. */
@RestController
@RequestMapping("/api/generation")
public class GenerationController {

    private final GenerationJobService jobService;

    public GenerationController(GenerationJobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<JobResponse> start(@Valid @RequestBody StartGenerationRequest request) {
        GenerationJob job = jobService.start(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobResponse.from(job));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> get(@PathVariable String id) {
        return jobService.find(id)
                .map(job -> ResponseEntity.ok(JobResponse.from(job)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<JobResponse> list() {
        return jobService.all().stream().map(JobResponse::from).toList();
    }
}
