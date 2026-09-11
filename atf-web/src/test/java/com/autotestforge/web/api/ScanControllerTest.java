package com.autotestforge.web.api;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.ClassTarget;
import com.autotestforge.core.domain.ScanPreview;
import com.autotestforge.core.exception.ScanException;
import com.autotestforge.web.job.GenerationJobService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScanController.class)
class ScanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GenerationJobService jobService;

    @Test
    @DisplayName("POST /api/scan returns every discovered type with eligibility")
    void scan_shouldReturnPreview() throws Exception {
        given(jobService.scan(any())).willReturn(new ScanPreview(Path.of("/repo"), BuildTool.MAVEN, List.of(
                new ClassTarget("com.acme.OrderService", ClassKind.CLASS, "Service", List.of("place"),
                        List.of("com.acme.Repo"), Path.of("/repo/OrderService.java"), true, null, null),
                new ClassTarget("com.acme.Repo", ClassKind.INTERFACE, null, List.of("find"), List.of(),
                        Path.of("/repo/Repo.java"), false, "interface", null))));

        mockMvc.perform(post("/api/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectPath": "/repo", "classes": ["com.acme.*"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buildTool").value("MAVEN"))
                .andExpect(jsonPath("$.discovered").value(2))
                .andExpect(jsonPath("$.eligible").value(1))
                .andExpect(jsonPath("$.classes[0].classFqn").value("com.acme.OrderService"))
                .andExpect(jsonPath("$.classes[0].eligible").value(true))
                .andExpect(jsonPath("$.classes[1].skipReason").value("interface"));
    }

    @Test
    @DisplayName("POST /api/scan validates the body and maps scan errors to a problem response")
    void scan_shouldRejectInvalidRequests() throws Exception {
        mockMvc.perform(post("/api/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        given(jobService.scan(any())).willThrow(new ScanException("No src/main/java source roots found under /repo"));
        mockMvc.perform(post("/api/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectPath": "/repo"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("No src/main/java source roots found under /repo"));
    }
}
