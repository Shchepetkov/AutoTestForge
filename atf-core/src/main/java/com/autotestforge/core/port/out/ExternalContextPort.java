package com.autotestforge.core.port.out;

import com.autotestforge.core.domain.ExternalTestContext;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.TestGenerationRequest;

/** Retrieves optional business/TMS context for a class under test. */
public interface ExternalContextPort {

    ExternalContextPort NO_OP = (classInfo, request) -> ExternalTestContext.empty();

    ExternalTestContext fetchContext(JavaClassInfo classInfo, TestGenerationRequest request);

    /**
     * Lifecycle hook invoked once per run after the last class has been
     * processed, letting adapters release per-run resources (for example MCP
     * server processes that were configured for this run only).
     */
    default void onRunFinished(TestGenerationRequest request) {
    }
}
