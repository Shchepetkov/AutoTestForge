package com.autotestforge.web;

import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.web.mcp.McpController;
import com.autotestforge.web.project.ProjectInspectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "atf.llm.provider=offline",
        "atf.validation.prefer-docker=false",
        "atf.workspace.allowed-roots=."
})
class AutoTestForgeWebApplicationTest {

    @Autowired
    private GenerateTestsUseCase generateTestsUseCase;

    @Autowired
    private ProjectInspectionService inspectionService;

    @Autowired
    private McpController mcpController;

    @Test
    void contextLoads_withAgentPipelineAndMcpTools() {
        assertThat(generateTestsUseCase).isNotNull();
        assertThat(inspectionService).isNotNull();
        assertThat(mcpController).isNotNull();
    }
}
