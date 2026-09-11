package com.autotestforge.core.port.in;

import com.autotestforge.core.domain.ScanPreview;
import com.autotestforge.core.domain.TestGenerationRequest;

/**
 * Driving port: scans a project and reports which types the pipeline would
 * generate tests for under the request's filters, without calling any LLM or
 * touching the project. Lets users pick classes before spending tokens.
 */
public interface ScanProjectUseCase {

    ScanPreview previewTargets(TestGenerationRequest request);
}
