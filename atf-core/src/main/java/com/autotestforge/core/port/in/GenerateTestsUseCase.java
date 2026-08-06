package com.autotestforge.core.port.in;

import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.core.domain.TestGenerationRequest;

/** Driving port: the single entry point of the application core. */
public interface GenerateTestsUseCase {

    TestGenerationReport generateTests(TestGenerationRequest request);
}
