package com.autotestforge.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGenerationRequestTest {

    private static final Path PROJECT = Path.of("/repo");

    @Test
    @DisplayName("builder defaults: sequential, two fix rounds, no overwrite, writes into the project")
    void builder_shouldApplyDefaults() {
        TestGenerationRequest request = TestGenerationRequest.builder(PROJECT).build();

        assertThat(request.parallelism()).isEqualTo(1);
        assertThat(request.maxFixAttempts()).isEqualTo(2);
        assertThat(request.overwriteExisting()).isFalse();
        assertThat(request.writesIntoProject()).isTrue();
        assertThat(request.includedClasses()).isEmpty();
        assertThat(request.excludedClasses()).isEmpty();
        assertThat(request.llmProvider()).isNull();
        assertThat(request.externalContext().enabled()).isFalse();
        assertThat(request.progressListener()).isSameAs(ProgressListener.NO_OP);
    }

    @Test
    @DisplayName("class filters are stripped, de-duplicated and blank entries dropped")
    void constructor_shouldNormalizeFilters() {
        TestGenerationRequest request = TestGenerationRequest.builder(PROJECT)
                .includedClasses(Arrays.asList(" OrderService ", "", null, "OrderService", "*Utils"))
                .excludedClasses(List.of("  "))
                .llmProvider("  ")
                .build();

        assertThat(request.includedClasses()).containsExactly("OrderService", "*Utils");
        assertThat(request.excludedClasses()).isEmpty();
        assertThat(request.llmProvider()).isNull();
    }

    @Test
    @DisplayName("dry run and output directory both leave the project untouched")
    void writesIntoProject_shouldBeFalse_forDryRunAndOutputDir() {
        assertThat(TestGenerationRequest.builder(PROJECT).dryRun(true).build().writesIntoProject()).isFalse();
        assertThat(TestGenerationRequest.builder(PROJECT).outputDir(Path.of("/out")).build().writesIntoProject())
                .isFalse();
    }

    @Test
    @DisplayName("invalid numeric settings are rejected")
    void constructor_shouldRejectInvalidValues() {
        assertThatThrownBy(() -> TestGenerationRequest.builder(PROJECT).maxFixAttempts(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestGenerationRequest.builder(PROJECT).parallelism(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestGenerationRequest.builder(PROJECT)
                .parallelism(TestGenerationRequest.MAX_PARALLELISM + 1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestGenerationRequest.builder(null).build())
                .isInstanceOf(NullPointerException.class);
    }
}
