package com.autotestforge.web.api;

import com.autotestforge.core.report.ReportFormatter;
import com.autotestforge.web.job.GenerationJob;
import com.autotestforge.web.job.GenerationJobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST API for starting generation runs, polling their progress, cancelling them and downloading reports. */
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
        return jobService.all().stream().map(job -> JobResponse.from(job, false)).toList();
    }

    /** Cooperative cancellation: the class in flight finishes, remaining classes are not started. */
    @DeleteMapping("/{id}")
    public ResponseEntity<JobResponse> cancel(@PathVariable String id) {
        return jobService.find(id)
                .map(job -> {
                    jobService.cancel(id);
                    return ResponseEntity.ok(JobResponse.from(job));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** Downloadable report of a finished job as JSON or Markdown. */
    @GetMapping("/{id}/report")
    public ResponseEntity<String> report(@PathVariable String id,
                                         @RequestParam(defaultValue = "markdown") String format) {
        return jobService.find(id)
                .map(job -> {
                    if (job.getReport() == null) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .contentType(MediaType.TEXT_PLAIN)
                                .body("Job " + id + " has no report yet (state: " + job.getState() + ")");
                    }
                    boolean json = "json".equalsIgnoreCase(format);
                    String body = json
                            ? ReportFormatter.toJson(job.getReport(), true)
                            : ReportFormatter.toMarkdown(job.getReport());
                    String shortId = id.length() > 8 ? id.substring(0, 8) : id;
                    String fileName = "autotestforge-report-" + shortId + (json ? ".json" : ".md");
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                            .contentType(json ? MediaType.APPLICATION_JSON : MediaType.TEXT_MARKDOWN)
                            .body(body);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
