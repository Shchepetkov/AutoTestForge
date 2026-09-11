package com.autotestforge.cli;

import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.core.port.in.ScanProjectUseCase;
import com.autotestforge.core.port.out.AiTestGeneratorPort;
import com.autotestforge.core.port.out.TestValidatorPort;
import com.autotestforge.spring.AtfProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(args = "--help")
@DisplayName("CLI context wires the full hexagon from the shared auto-configuration alone")
class AutoTestForgeCliApplicationTest {

    @Autowired
    private GenerateTestsUseCase generateTestsUseCase;

    @Autowired
    private ScanProjectUseCase scanProjectUseCase;

    @Autowired
    private AiTestGeneratorPort aiTestGenerator;

    @Autowired
    private TestValidatorPort testValidator;

    @Autowired
    private AtfProperties properties;

    @Autowired
    private AutoTestForgeCliApplication application;

    @Test
    void withoutSpringProperties_shouldDropConfigurationOverrides() {
        assertThat(AutoTestForgeCliApplication.withoutSpringProperties(
                "generate", "--atf.validation.prefer-docker=false", "-p", "/repo", "--spring.main.banner-mode=off",
                "--logging.level.root=debug", "--validate"))
                .containsExactly("generate", "-p", "/repo", "--validate");
    }

    @Test
    void contextLoads_withAllPortsWired() {
        assertThat(generateTestsUseCase).isNotNull();
        assertThat(scanProjectUseCase).isNotNull();
        assertThat(aiTestGenerator).isNotNull();
        assertThat(testValidator).isNotNull();
        assertThat(properties.llm().provider()).isEqualTo("ollama");
        assertThat(properties.generation().parallelism()).isEqualTo(1);
        assertThat(application.getExitCode()).isZero();
    }
}
