package com.autotestforge.validator;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.port.out.TestValidatorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

import java.nio.file.Path;

/**
 * Picks the isolation strategy at runtime: Docker (preferred, fully isolated)
 * when a daemon is reachable, otherwise a local process run. The Docker probe
 * result is cached because it is expensive.
 */
public class AdaptiveTestExecutor implements TestValidatorPort {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveTestExecutor.class);

    private final DockerTestExecutor dockerExecutor;
    private final LocalProcessTestExecutor localExecutor;
    private final boolean preferDocker;
    private volatile Boolean dockerAvailable;

    public AdaptiveTestExecutor(DockerTestExecutor dockerExecutor,
                                LocalProcessTestExecutor localExecutor,
                                boolean preferDocker) {
        this.dockerExecutor = dockerExecutor;
        this.localExecutor = localExecutor;
        this.preferDocker = preferDocker;
    }

    @Override
    public ValidationResult runTests(Path projectRoot, BuildTool buildTool, String testClassFqn) {
        if (preferDocker && isDockerAvailable()) {
            return dockerExecutor.runTests(projectRoot, buildTool, testClassFqn);
        }
        log.info("Validating via local process (docker preferred={}, available={})",
                preferDocker, dockerAvailable);
        return localExecutor.runTests(projectRoot, buildTool, testClassFqn);
    }

    private boolean isDockerAvailable() {
        Boolean available = dockerAvailable;
        if (available == null) {
            synchronized (this) {
                if (dockerAvailable == null) {
                    try {
                        dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
                    } catch (RuntimeException e) {
                        log.warn("Docker probe failed: {}", e.getMessage());
                        dockerAvailable = false;
                    }
                }
                available = dockerAvailable;
            }
        }
        return available;
    }
}
