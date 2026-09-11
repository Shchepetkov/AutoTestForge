package com.autotestforge.web.api;

import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.GenerationStatus;
import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.web.job.GenerationJob;
import com.autotestforge.web.job.GenerationJobService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GenerationController.class)
class GenerationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GenerationJobService jobService;

    @Test
    @DisplayName("POST /api/generation starts a job and returns 202 with its id")
    void start_shouldReturnAccepted_whenRequestIsValid() throws Exception {
        given(jobService.start(any())).willReturn(new GenerationJob("job-1", "C:/projects/demo"));

        mockMvc.perform(post("/api/generation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectPath": "C:/projects/demo", "validate": true, "parallelism": 4,
                                 "excludes": ["*Config"], "overwrite": true}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("job-1"))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.processedClasses").value(0));
    }

    @Test
    @DisplayName("POST /api/generation rejects a blank project path and out-of-range parallelism")
    void start_shouldReturnBadRequest_whenInvalid() throws Exception {
        mockMvc.perform(post("/api/generation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectPath": "  "}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/generation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectPath": "/repo", "parallelism": 64}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/generation/{id} returns results with sources; 404 for unknown jobs")
    void get_shouldReturnJobWithSources() throws Exception {
        GenerationJob job = finishedJob();
        given(jobService.find("job-1")).willReturn(Optional.of(job));
        given(jobService.find("nope")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/generation/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.results[0].status").value("VALIDATED"))
                .andExpect(jsonPath("$.results[0].testSource").value("class OrderServiceTest {}"))
                .andExpect(jsonPath("$.results[1].status").value("SKIPPED"))
                .andExpect(jsonPath("$.summary.skipped").value(1))
                .andExpect(jsonPath("$.summary.succeeded").value(1));

        mockMvc.perform(get("/api/generation/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/generation lists jobs without the generated sources")
    void list_shouldOmitSources() throws Exception {
        given(jobService.all()).willReturn(List.of(finishedJob()));

        mockMvc.perform(get("/api/generation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("job-1"))
                .andExpect(jsonPath("$[0].results[0].testSource").doesNotExist());
    }

    @Test
    @DisplayName("DELETE /api/generation/{id} requests cancellation")
    void cancel_shouldDelegateToService() throws Exception {
        GenerationJob job = new GenerationJob("job-1", "/repo");
        given(jobService.find("job-1")).willReturn(Optional.of(job));
        given(jobService.cancel("job-1")).willReturn(true);

        mockMvc.perform(delete("/api/generation/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("job-1"));
        verify(jobService).cancel("job-1");

        given(jobService.find("missing")).willReturn(Optional.empty());
        mockMvc.perform(delete("/api/generation/missing")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/generation/{id}/report downloads Markdown or JSON, 409 while still running")
    void report_shouldRenderRequestedFormat() throws Exception {
        given(jobService.find("job-1")).willReturn(Optional.of(finishedJob()));
        given(jobService.find("running")).willReturn(Optional.of(new GenerationJob("running", "/repo")));

        mockMvc.perform(get("/api/generation/job-1/report"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"autotestforge-report-job-1.md\""))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("# AutoTestForge report")));

        mockMvc.perform(get("/api/generation/job-1/report").param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.summary.processed").value(2));

        mockMvc.perform(get("/api/generation/running/report"))
                .andExpect(status().isConflict());
    }

    private static GenerationJob finishedJob() {
        GenerationJob job = new GenerationJob("job-1", "/repo");
        ClassGenerationResult validated = new ClassGenerationResult("com.acme.OrderService",
                "com.acme.OrderServiceTest", GenerationStatus.VALIDATED,
                Path.of("/repo/src/test/java/com/acme/OrderServiceTest.java"), 1, null, "class OrderServiceTest {}");
        ClassGenerationResult skipped = ClassGenerationResult.skipped("com.acme.TextUtils", "com.acme.TextUtilsTest",
                Path.of("/repo/src/test/java/com/acme/TextUtilsTest.java"));
        job.setTotalClasses(2);
        job.addResult(validated);
        job.addResult(skipped);
        job.complete(new TestGenerationReport(Path.of("/repo"), List.of(validated, skipped), Instant.now(),
                Duration.ofSeconds(3)));
        return job;
    }
}
