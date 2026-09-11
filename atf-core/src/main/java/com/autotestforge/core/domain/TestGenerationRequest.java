package com.autotestforge.core.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Input of the test generation use case. Built by driving adapters (CLI/web).
 *
 * @param projectPath       root of the target project
 * @param includedClasses   simple or fully qualified names / wildcard patterns to restrict generation to; empty = all
 * @param excludedClasses   names / wildcard patterns to leave out, applied after {@code includedClasses}
 * @param llmProvider       provider override (e.g. "ollama", "openai", "offline"); null = configured default
 * @param validate          run generated tests in the isolated environment and self-correct on failure
 * @param dryRun            generate only, never touch the target project
 * @param overwriteExisting regenerate classes that already have a test; otherwise they are reported as skipped
 * @param maxFixAttempts    maximum LLM self-correction rounds per class
 * @param parallelism       number of classes processed concurrently (LLM calls); validation runs are serialized
 * @param outputDir         when set, tests are written under this directory (mirroring the module layout)
 *                          instead of the target project; implies no build-file changes and no validation
 * @param externalContext   optional business/TMS context lookup settings
 * @param progressListener  progress callback, never null
 */
public record TestGenerationRequest(
        Path projectPath,
        List<String> includedClasses,
        List<String> excludedClasses,
        String llmProvider,
        boolean validate,
        boolean dryRun,
        boolean overwriteExisting,
        int maxFixAttempts,
        int parallelism,
        Path outputDir,
        ExternalContextRequest externalContext,
        ProgressListener progressListener) {

    public static final int MAX_PARALLELISM = 16;

    public TestGenerationRequest {
        Objects.requireNonNull(projectPath, "projectPath must not be null");
        includedClasses = normalizeNames(includedClasses);
        excludedClasses = normalizeNames(excludedClasses);
        llmProvider = llmProvider == null || llmProvider.isBlank() ? null : llmProvider.strip();
        externalContext = externalContext == null ? ExternalContextRequest.disabled() : externalContext;
        progressListener = progressListener == null ? ProgressListener.NO_OP : progressListener;
        if (maxFixAttempts < 0) {
            throw new IllegalArgumentException("maxFixAttempts must be >= 0");
        }
        if (parallelism < 1 || parallelism > MAX_PARALLELISM) {
            throw new IllegalArgumentException("parallelism must be between 1 and " + MAX_PARALLELISM);
        }
    }

    private static List<String> normalizeNames(List<String> names) {
        return names == null
                ? List.of()
                : names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    /** True when the target project itself is left untouched (dry run or alternate output directory). */
    public boolean writesIntoProject() {
        return !dryRun && outputDir == null;
    }

    public static Builder builder(Path projectPath) {
        return new Builder(projectPath);
    }

    public static final class Builder {
        private final Path projectPath;
        private List<String> includedClasses = List.of();
        private List<String> excludedClasses = List.of();
        private String llmProvider;
        private boolean validate;
        private boolean dryRun;
        private boolean overwriteExisting;
        private int maxFixAttempts = 2;
        private int parallelism = 1;
        private Path outputDir;
        private ExternalContextRequest externalContext = ExternalContextRequest.disabled();
        private ProgressListener progressListener = ProgressListener.NO_OP;

        private Builder(Path projectPath) {
            this.projectPath = projectPath;
        }

        public Builder includedClasses(List<String> includedClasses) {
            this.includedClasses = includedClasses;
            return this;
        }

        public Builder excludedClasses(List<String> excludedClasses) {
            this.excludedClasses = excludedClasses;
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

        public Builder overwriteExisting(boolean overwriteExisting) {
            this.overwriteExisting = overwriteExisting;
            return this;
        }

        public Builder maxFixAttempts(int maxFixAttempts) {
            this.maxFixAttempts = maxFixAttempts;
            return this;
        }

        public Builder parallelism(int parallelism) {
            this.parallelism = parallelism;
            return this;
        }

        public Builder outputDir(Path outputDir) {
            this.outputDir = outputDir;
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
                    projectPath, includedClasses, excludedClasses, llmProvider, validate, dryRun,
                    overwriteExisting, maxFixAttempts, parallelism, outputDir, externalContext, progressListener);
        }
    }
}
