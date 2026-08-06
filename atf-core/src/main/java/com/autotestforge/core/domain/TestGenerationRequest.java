package com.autotestforge.core.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Input of the test generation use case. Built by driving adapters (CLI/web).
 *
 * @param projectPath      root of the target project
 * @param includedClasses  simple or fully qualified names to restrict generation to; empty = all
 * @param llmProvider      provider override (e.g. "ollama", "openai", "offline"); null = configured default
 * @param validate         run generated tests in the isolated environment and self-correct on failure
 * @param dryRun           generate only, never touch the target project
 * @param maxFixAttempts   maximum LLM self-correction rounds per class
 * @param externalContext  optional business/TMS context lookup settings
 * @param progressListener progress callback, never null
 */
public record TestGenerationRequest(
        Path projectPath,
        List<String> includedClasses,
        String llmProvider,
        boolean validate,
        boolean dryRun,
        int maxFixAttempts,
        ExternalContextRequest externalContext,
        ProgressListener progressListener) {

    public TestGenerationRequest {
        Objects.requireNonNull(projectPath, "projectPath must not be null");
        includedClasses = includedClasses == null ? List.of() : List.copyOf(includedClasses);
        externalContext = externalContext == null ? ExternalContextRequest.disabled() : externalContext;
        progressListener = progressListener == null ? ProgressListener.NO_OP : progressListener;
        if (maxFixAttempts < 0) {
            throw new IllegalArgumentException("maxFixAttempts must be >= 0");
        }
    }

    public static Builder builder(Path projectPath) {
        return new Builder(projectPath);
    }

    public static final class Builder {
        private final Path projectPath;
        private List<String> includedClasses = List.of();
        private String llmProvider;
        private boolean validate;
        private boolean dryRun;
        private int maxFixAttempts = 2;
        private ExternalContextRequest externalContext = ExternalContextRequest.disabled();
        private ProgressListener progressListener = ProgressListener.NO_OP;

        private Builder(Path projectPath) {
            this.projectPath = projectPath;
        }

        public Builder includedClasses(List<String> includedClasses) {
            this.includedClasses = includedClasses;
            return this;
        }

        public Builder llmProvider(String llmProvider) {
            this.llmProvider = llmProvider;
            return this;
        }

        public Builder validate(boolean validate) {
            this.validate = validate;
            return this;
        }

        public Builder dryRun(boolean dryRun) {
            this.dryRun = dryRun;
            return this;
        }

        public Builder maxFixAttempts(int maxFixAttempts) {
            this.maxFixAttempts = maxFixAttempts;
            return this;
        }

        public Builder externalContext(ExternalContextRequest externalContext) {
            this.externalContext = externalContext;
            return this;
        }

        public Builder progressListener(ProgressListener progressListener) {
            this.progressListener = progressListener;
            return this;
        }

        public TestGenerationRequest build() {
            return new TestGenerationRequest(
                    projectPath, includedClasses, llmProvider, validate, dryRun, maxFixAttempts,
                    externalContext, progressListener);
        }
    }
}
