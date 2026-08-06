package com.autotestforge.core.port.out;

import com.autotestforge.core.domain.ExternalTestContext;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.TestGenerationRequest;

/** Retrieves optional business/TMS context for a class under test. */
public interface ExternalContextPort {

    ExternalContextPort NO_OP = (classInfo, request) -> ExternalTestContext.empty();

    ExternalTestContext fetchContext(JavaClassInfo classInfo, TestGenerationRequest request);
}
