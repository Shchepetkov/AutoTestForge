package com.autotestforge.web.config;

import com.autotestforge.ai.LlmResponseParser;
import com.autotestforge.ai.TestPromptBuilder;
import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.core.port.out.BuildToolPort;
import com.autotestforge.core.port.out.ExternalContextPort;
import com.autotestforge.core.port.out.ProjectScannerPort;
import com.autotestforge.core.port.out.TestValidatorPort;
import com.autotestforge.core.port.out.TestWriterPort;
import com.autotestforge.mcp.McpContextSource;
import com.autotestforge.mcp.McpExternalContextProvider;
import com.autotestforge.scanner.JavaParserProjectScanner;
import com.autotestforge.validator.AdaptiveTestExecutor;
import com.autotestforge.validator.DockerTestExecutor;
import com.autotestforge.validator.JUnitXmlReportParser;
import com.autotestforge.validator.LocalProcessTestExecutor;
import com.autotestforge.writer.BuildToolAdapter;
import com.autotestforge.writer.GradleBuildUpdater;
import com.autotestforge.writer.MavenPomUpdater;
import com.autotestforge.writer.TestFileWriter;
import com.autotestforge.web.llm.GenerationUseCaseFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Hexagonal wiring for the web application (mirrors the CLI wiring). */
@Configuration
public class AtfConfiguration {

    @Bean
    public ProjectScannerPort projectScanner() {
        return new JavaParserProjectScanner();
    }

    @Bean
    public TestPromptBuilder testPromptBuilder() {
        return new TestPromptBuilder();
    }

    @Bean
    public LlmResponseParser llmResponseParser() {
        return new LlmResponseParser();
    }

    @Bean
    public TestWriterPort testWriter() {
        return new TestFileWriter();
    }

    @Bean
    public BuildToolPort buildToolPort() {
        return new BuildToolAdapter(new MavenPomUpdater(), new GradleBuildUpdater());
    }

    @Bean
    public TestValidatorPort testValidator(AtfProperties properties) {
        AtfProperties.Validation validation = properties.validation();
        JUnitXmlReportParser reportParser = new JUnitXmlReportParser();
        return new AdaptiveTestExecutor(
                new DockerTestExecutor(validation.mavenImage(), validation.gradleImage(),
                        validation.cacheDir(), reportParser, validation.timeout()),
                new LocalProcessTestExecutor(validation.timeout(), reportParser),
                validation.preferDocker());
    }

    @Bean
    public ExternalContextPort externalContextPort(AtfProperties properties) {
        AtfProperties.Context context = properties.context();
        List<McpContextSource> sources = context == null || !context.enabled() ? List.of() : context.sources().stream()
                .map(this::toMcpSource)
                .toList();
        return new McpExternalContextProvider(sources);
    }

    private McpContextSource toMcpSource(AtfProperties.ContextSource source) {
        return new McpContextSource(source.name(), source.enabled(), source.command(), source.args(),
                source.toolName(), source.queryArgument(), source.queryTemplate(), source.arguments(),
                source.timeout(), source.maxChars());
    }

    @Bean
    public GenerateTestsUseCase generateTestsUseCase(GenerationUseCaseFactory factory) {
        // Resolve lazily, so a missing default-provider key cannot prevent opening the setup website.
        return request -> factory.create(null, request.llmProvider()).generateTests(request);
    }
}
