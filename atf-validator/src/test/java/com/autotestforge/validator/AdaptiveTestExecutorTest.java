package com.autotestforge.validator;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.ValidationResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdaptiveTestExecutorTest {

    private final DockerTestExecutor docker = mock(DockerTestExecutor.class);
    private final LocalProcessTestExecutor local = mock(LocalProcessTestExecutor.class);

    @Test
    @DisplayName("when Docker is not preferred the local executor is used without probing")
    void runTests_shouldUseLocalExecutor_whenDockerNotPreferred() {
        AdaptiveTestExecutor executor = new AdaptiveTestExecutor(docker, local, false);
        when(local.runTests(any(), any(), any(), any())).thenReturn(ValidationResult.success("ok"));

        ValidationResult result = executor.runTests(Path.of("/repo"), BuildTool.MAVEN, "com.acme.T", null);

        assertThat(result.success()).isTrue();
        verify(docker, never()).runTests(any(), any(), any(), any());
    }

    @Test
    @DisplayName("without any Docker hints on the machine the probe is skipped and the local executor used")
    void runTests_shouldFallBackToLocal_whenNoDockerHints() {
        Assumptions.assumeFalse(AdaptiveTestExecutor.dockerEnvironmentHinted(),
                "machine has a Docker setup; the no-hint path cannot be exercised here");
        AdaptiveTestExecutor executor = new AdaptiveTestExecutor(docker, local, true);
        when(local.runTests(any(), any(), any(), any())).thenReturn(ValidationResult.success("ok"));

        executor.runTests(Path.of("/repo"), BuildTool.MAVEN, "com.acme.T", null);
        executor.runTests(Path.of("/repo"), BuildTool.MAVEN, "com.acme.T", null);

        verify(docker, never()).runTests(any(), any(), any(), any());
        verify(local, times(2)).runTests(any(), any(), any(), any());
    }
}
