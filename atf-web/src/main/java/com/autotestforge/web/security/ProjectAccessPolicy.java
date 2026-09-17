package com.autotestforge.web.security;

import com.autotestforge.web.config.AtfProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Restricts every server-side project operation to configured workspace roots. */
@Component
public class ProjectAccessPolicy {

    private final List<Path> allowedRoots;

    public ProjectAccessPolicy(AtfProperties properties) {
        AtfProperties.Workspace workspace = properties.workspace();
        List<String> configured = workspace == null ? List.of(".") : workspace.allowedRoots();
        this.allowedRoots = configured.stream().map(Path::of).map(this::canonicalRoot).toList();
    }

    /** Returns a canonical directory after verifying that it is inside an allowed root. */
    public Path requireAllowedProject(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) {
            throw new IllegalArgumentException("projectPath must not be blank");
        }
        Path candidate = canonicalDirectory(Path.of(requestedPath), false);
        boolean allowed = allowedRoots.stream().anyMatch(candidate::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("Project path is outside configured workspace roots");
        }
        return candidate;
    }

    public List<Path> allowedRoots() {
        return allowedRoots;
    }

    private Path canonicalRoot(Path root) {
        return canonicalDirectory(root, true);
    }

    private Path canonicalDirectory(Path path, boolean configuredRoot) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            String prefix = configuredRoot ? "Configured workspace root" : "Project path";
            throw new IllegalArgumentException(prefix + " does not exist or is not a directory: " + normalized);
        }
        try {
            return normalized.toRealPath();
        } catch (AccessDeniedException e) {
            // Some Windows OneDrive/reparse-point directories are readable but reject
            // real-path resolution. Docker/Linux still use the stronger canonical path.
            return normalized;
        } catch (IOException e) {
            String prefix = configuredRoot ? "Configured workspace root" : "Project path";
            throw new IllegalArgumentException(prefix + " cannot be resolved: " + normalized, e);
        }
    }
}
