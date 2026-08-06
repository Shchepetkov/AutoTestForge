package com.autotestforge.cli;

import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.core.port.out.AiTestGeneratorPort;
import com.autotestforge.core.port.out.TestValidatorPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(args = "--help")
@DisplayName("CLI context wires the full hexagon from configuration alone")
class AutoTestForgeCliApplicationTest {

    @Autowired
    private GenerateTestsUseCase generateTestsUseCase;

    @Autowired
    private AiTestGeneratorPort aiTestGenerator;

    @Autowired
    private TestValidatorPort testValidator;

    @Test
    void contextLoads_withAllPortsWired() {
        assertThat(generateTestsUseCase).isNotNull();
        assertThat(aiTestGenerator).isNotNull();
        assertThat(testValidator).isNotNull();
    }
}
