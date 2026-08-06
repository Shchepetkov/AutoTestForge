package com.autotestforge.web.config;

import com.autotestforge.ai.ChatModels;
import com.autotestforge.ai.LangChain4jTestGenerator;
import com.autotestforge.ai.LlmResponseParser;
import com.autotestforge.ai.OfflineTestGenerator;
import com.autotestforge.ai.RoutingTestGenerator;
import com.autotestforge.ai.TestPromptBuilder;
import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.core.port.out.AiTestGeneratorPort;
import com.autotestforge.core.port.out.BuildToolPort;
import com.autotestforge.core.port.out.ProjectScannerPort;
import com.autotestforge.core.port.out.TestValidatorPort;
import com.autotestforge.core.port.out.TestWriterPort;
import com.autotestforge.core.service.TestGenerationService;
import com.autotestforge.scanner.JavaParserProjectScanner;
import com.autotestforge.validator.AdaptiveTestExecutor;
import com.autotestforge.validator.DockerTestExecutor;
import com.autotestforge.validator.JUnitXmlReportParser;
import com.autotestforge.validator.LocalProcessTestExecutor;
import com.autotestforge.writer.BuildToolAdapter;
import com.autotestforge.writer.GradleBuildUpdater;
import com.autotestforge.writer.MavenPomUpdater;
import com.autotestforge.writer.TestFileWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

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
    public AiTestGeneratorPort aiTestGenerator(AtfProperties properties,
                                               TestPromptBuilder promptBuilder,
                                               LlmResponseParser responseParser) {
        AtfProperties.Llm llm = properties.llm();
        Map<String, AiTestGeneratorPort> providers = new HashMap<>();
        providers.put("offline", new OfflineTestGenerator());
        providers.put("ollama", new LangChain4jTestGenerator(
                ChatModels.ollama(llm.ollama().baseUrl(), llm.ollama().model(),
                        llm.temperature(), llm.ollama().timeout()),
                promptBuilder, responseParser, llm.maxRetries(), llm.retryBackoffMillis()));
        if (llm.openai().isConfigured()) {
            providers.put("openai", new LangChain4jTestGenerator(
                    ChatModels.openAi(llm.openai().apiKey(), llm.openai().model(),
                            llm.temperature(), llm.openai().timeout()),
                    promptBuilder, responseParser, llm.maxRetries(), llm.retryBackoffMillis()));
        }
        return new RoutingTestGenerator(providers, llm.provider());
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
                        validation.cacheDir(), reportParser),
                new LocalProcessTestExecutor(validation.timeout(), reportParser),
                validation.preferDocker());
    }

    @Bean
    public GenerateTestsUseCase generateTestsUseCase(ProjectScannerPort scanner,
                                                     AiTestGeneratorPort aiTestGenerator,
                                                     TestWriterPort testWriter,
                                                     BuildToolPort buildToolPort,
                                                     TestValidatorPort testValidator) {
        return new TestGenerationService(scanner, aiTestGenerator, testWriter, buildToolPort, testValidator);
    }
}
