package com.autotestforge.web.llm;

import com.autotestforge.core.port.in.GenerateTestsUseCase;
import com.autotestforge.core.port.out.BuildToolPort;
import com.autotestforge.core.port.out.ExternalContextPort;
import com.autotestforge.core.port.out.ProjectScannerPort;
import com.autotestforge.core.port.out.TestValidatorPort;
import com.autotestforge.core.port.out.TestWriterPort;
import com.autotestforge.core.service.TestGenerationService;
import org.springframework.stereotype.Component;

/** A job owns its generator; generation and repair cannot see another job's connection. */
@Component
public class GenerationUseCaseFactory {
    private final ProjectScannerPort scanner;
    private final TestWriterPort writer;
    private final BuildToolPort buildTool;
    private final TestValidatorPort validator;
    private final ExternalContextPort context;
    private final LlmConnectionService connections;

    public GenerationUseCaseFactory(ProjectScannerPort scanner, TestWriterPort writer,
                                    BuildToolPort buildTool, TestValidatorPort validator,
                                    ExternalContextPort context, LlmConnectionService connections) {
        this.scanner = scanner;
        this.writer = writer;
        this.buildTool = buildTool;
        this.validator = validator;
        this.context = context;
        this.connections = connections;
    }

    public GenerateTestsUseCase create(LlmConnectionRequest connection, String provider) {
        return new TestGenerationService(scanner, connections.generator(connections.resolve(connection, provider, true)),
                writer, buildTool, validator, context);
    }
}
