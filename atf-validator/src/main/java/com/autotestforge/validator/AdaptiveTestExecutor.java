package com.autotestforge.validator;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.port.out.TestValidatorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Picks the isolation strategy at runtime: Docker (preferred, fully isolated)
 * when a daemon is reachable, otherwise a local process run. The Docker probe
 * result is cached because it is expensive. Machines without any trace of a
 * Docker setup skip the Testcontainers probe entirely, which avoids a noisy
 * error stack trace and a multi-second delay on the first validation.
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
    public ValidationResult runTests(Path projectRoot, BuildTool buildTool, String testClassFqn, Path testFile) {
        if (preferDocker && isDockerAvailable()) {
            return dockerExecutor.runTests(projectRoot, buildTool, testClassFqn, testFile);
        }
        log.info("Validating via local process (docker preferred={}, available={})",
                preferDocker, dockerAvailable);
        return localExecutor.runTests(projectRoot, buildTool, testClassFqn, testFile);
    }

    private boolean isDockerAvailable() {
        Boolean available = dockerAvailable;
        if (available == null) {
            synchronized (this) {
                if (dockerAvailable == null) {
                    dockerAvailable = probeDocker();
                }
                available = dockerAvailable;
            }
        }
        return available;
    }

    private boolean probeDocker() {
        if (!dockerEnvironmentHinted()) {
            log.info("No Docker daemon configured on this machine (no DOCKER_HOST, socket or "
                    + "Testcontainers settings found) - generated tests will run in a local process");
            return false;
        }
        try {
            boolean available = DockerClientFactory.instance().isDockerAvailable();
            log.info("Docker daemon {}", available ? "available - using container sandbox" : "unreachable");
            return available;
        } catch (RuntimeException e) {
            log.warn("Docker probe failed: {}", e.getMessage());
            return false;
        }
    }

    /** Cheap heuristics covering Linux sockets, Docker Desktop (macOS/Windows), remote hosts and TC settings. */
    static boolean dockerEnvironmentHinted() {
        if (hasText(System.getenv("DOCKER_HOST")) || hasText(System.getenv("TESTCONTAINERS_DOCKER_HOST"))
                || hasText(System.getenv("TESTCONTAINERS_HOST_OVERRIDE"))) {
            return true;
        }
        String home = System.getProperty("user.home", "");
        List<Path> candidates = List.of(
                Path.of("/var/run/docker.sock"),
                Path.of(home, ".docker", "run", "docker.sock"),
                Path.of(home, ".docker", "desktop", "docker.sock"),
                Path.of(home, ".colima", "default", "docker.sock"),
                Path.of(home, ".rd", "docker.sock"),
                Path.of(home, ".testcontainers.properties"),
                Path.of("\\\\.\\pipe\\docker_engine"));
        for (Path candidate : candidates) {
            try {
                if (Files.exists(candidate)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // an invalid path on this OS simply is not a hint
            }
        }
        return false;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
