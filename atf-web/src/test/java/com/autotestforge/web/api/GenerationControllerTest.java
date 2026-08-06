package com.autotestforge.web.api;

import com.autotestforge.web.job.GenerationJob;
import com.autotestforge.web.job.GenerationJobService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                                {"projectPath": "C:/projects/demo", "validate": true}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("job-1"))
                .andExpect(jsonPath("$.state").value("RUNNING"));
    }

    @Test
    @DisplayName("POST /api/generation rejects a blank project path")
    void start_shouldReturnBadRequest_whenProjectPathIsBlank() throws Exception {
        mockMvc.perform(post("/api/generation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectPath": "  "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/generation/{id} returns 404 for unknown jobs")
    void get_shouldReturnNotFound_whenJobDoesNotExist() throws Exception {
        given(jobService.find("nope")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/generation/nope"))
                .andExpect(status().isNotFound());
    }
}
