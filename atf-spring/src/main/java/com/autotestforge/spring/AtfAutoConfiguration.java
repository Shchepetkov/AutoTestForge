package com.autotestforge.spring;

import com.autotestforge.ai.ChatModels;
import com.autotestforge.ai.GeneratedTestNormalizer;
import com.autotestforge.ai.LangChain4jTestGenerator;
import com.autotestforge.ai.LlmResponseParser;
import com.autotestforge.ai.OfflineTestGenerator;
import com.autotestforge.ai.RoutingTestGenerator;
import com.autotestforge.ai.TestPromptBuilder;
import com.autotestforge.core.port.out.AiTestGeneratorPort;
import com.autotestforge.core.port.out.BuildToolPort;
import com.autotestforge.core.port.out.ExternalContextPort;
import com.autotestforge.core.port.out.ProjectScannerPort;
import com.autotestforge.core.port.out.TestValidatorPort;
import com.autotestforge.core.port.out.TestWriterPort;
import com.autotestforge.core.service.TestGenerationService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hexagonal wiring shared by every driving adapter (CLI, web): plain,
 * framework-free adapters are instantiated here and plugged into the core
 * use cases. Every bean is {@link ConditionalOnMissingBean}, so an application
 * can replace any adapter by declaring its own bean.
 */
@AutoConfiguration
@EnableConfigurationProperties(AtfProperties.class)
@PropertySource("classpath:atf-defaults.properties")
public class AtfAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtfAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ProjectScannerPort projectScanner() {
        return new JavaParserProjectScanner();
    }

    @Bean
    @ConditionalOnMissingBean
    public TestPromptBuilder testPromptBuilder() {
        return new TestPromptBuilder();
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmResponseParser llmResponseParser() {
        return new LlmResponseParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public GeneratedTestNormalizer generatedTestNormalizer() {
        return new GeneratedTestNormalizer();
    }

    /**
     * Registers every usable provider: "offline" and "ollama" are always
     * available, "openai" and "anthropic" only when configured. The default
     * comes from {@code atf.llm.provider} and can be overridden per run.
     */
    @Bean
    @ConditionalOnMissingBean(AiTestGeneratorPort.class)
    public RoutingTestGenerator aiTestGenerator(AtfProperties properties,
                                                TestPromptBuilder promptBuilder,
                                                LlmResponseParser responseParser,
                                                GeneratedTestNormalizer normalizer) {
        AtfProperties.Llm llm = properties.llm();
        Map<String, AiTestGeneratorPort> providers = new LinkedHashMap<>();
        providers.put("offline", new OfflineTestGenerator());
        providers.put("ollama", new LangChain4jTestGenerator(
                ChatModels.ollama(llm.ollama().baseUrl(), llm.ollama().model(), llm.temperature(),
                        llm.ollama().timeout()),
                promptBuilder, responseParser, normalizer, llm.maxRetries(), llm.retryBackoffMillis(), true));
        if (llm.openai() != null && llm.openai().isConfigured()) {
            providers.put("openai", new LangChain4jTestGenerator(
                    ChatModels.openAi(llm.openai().baseUrl(), llm.openai().apiKey(), llm.openai().model(),
                            llm.temperature(), llm.openai().timeout()),
                    promptBuilder, responseParser, normalizer, llm.maxRetries(), llm.retryBackoffMillis(), true));
        }
        if (llm.anthropic() != null && llm.anthropic().isConfigured()) {
            providers.put("anthropic", new LangChain4jTestGenerator(
                    ChatModels.anthropic(llm.anthropic().baseUrl(), llm.anthropic().apiKey(),
                            llm.anthropic().model(), llm.temperature(), llm.anthropic().maxTokens(),
                            llm.anthropic().timeout()),
                    promptBuilder, responseParser, normalizer, llm.maxRetries(), llm.retryBackoffMillis(), true));
        }
        log.info("LLM providers available: {} (default: {})", providers.keySet(), llm.provider());
        return new RoutingTestGenerator(providers, llm.provider());
    }

    @Bean
    @ConditionalOnMissingBean
    public TestWriterPort testWriter() {
        return new TestFileWriter();
    }

    @Bean
    @ConditionalOnMissingBean
    public BuildToolPort buildToolPort() {
        return new BuildToolAdapter(new MavenPomUpdater(), new GradleBuildUpdater());
    }

    @Bean
    @ConditionalOnMissingBean
    public TestValidatorPort testValidator(AtfProperties properties) {
        AtfProperties.Validation validation = properties.validation();
        JUnitXmlReportParser reportParser = new JUnitXmlReportParser();
        return new AdaptiveTestExecutor(
                new DockerTestExecutor(validation.mavenImage(), validation.gradleImage(),
                        validation.cacheDir(), reportParser),
                new LocalProcessTestExecutor(validation.timeout(), reportParser),
                validation.preferDocker());
    }

    /**
     * Statically configured MCP sources are used only when
     * {@code atf.context.enabled} is true; sources supplied per run (web form)
     * always work, so the provider is registered unconditionally.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ExternalContextPort.class)
    public McpExternalContextProvider externalContextPort(AtfProperties properties) {
        AtfProperties.Context context = properties.context();
        List<McpContextSource> sources = !context.enabled()
                ? List.of()
                : context.sources().stream().map(this::toMcpSource).toList();
        if (!sources.isEmpty()) {
            log.info("External MCP context sources configured: {}",
                    sources.stream().map(McpContextSource::name).toList());
        }
        return new McpExternalContextProvider(sources);
    }

    private McpContextSource toMcpSource(AtfProperties.ContextSource source) {
        return new McpContextSource(source.name(), source.enabled(), source.command(), source.args(),
                source.toolName(), source.queryArgument(), source.queryTemplate(), source.arguments(),
                source.timeout(), source.maxChars());
    }

    /** Implements both {@code GenerateTestsUseCase} and {@code ScanProjectUseCase}. */
    @Bean
    @ConditionalOnMissingBean
    public TestGenerationService testGenerationService(ProjectScannerPort scanner,
                                                       AiTestGeneratorPort aiTestGenerator,
                                                       TestWriterPort testWriter,
                                                       BuildToolPort buildToolPort,
                                                       TestValidatorPort testValidator,
                                                       ExternalContextPort externalContextPort) {
        return new TestGenerationService(scanner, aiTestGenerator, testWriter, buildToolPort,
                testValidator, externalContextPort);
    }
}
