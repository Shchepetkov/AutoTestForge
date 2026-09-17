package com.autotestforge.web.security;

import com.autotestforge.web.config.AtfProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectAccessPolicyTest {

    @TempDir
    Path workspace;

    @TempDir
    Path outside;

    @Test
    void requireAllowedProject_shouldAcceptDirectoryInsideWorkspace() throws Exception {
        Path project = java.nio.file.Files.createDirectory(workspace.resolve("project"));
        ProjectAccessPolicy policy = policy(workspace);

        assertThat(policy.requireAllowedProject(project.toString())).isEqualTo(project.toRealPath());
    }

    @Test
    void requireAllowedProject_shouldRejectDirectoryOutsideWorkspace() {
        ProjectAccessPolicy policy = policy(workspace);

        assertThatThrownBy(() -> policy.requireAllowedProject(outside.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside configured workspace");
    }

    private ProjectAccessPolicy policy(Path allowedRoot) {
        AtfProperties properties = new AtfProperties(null, null, null,
                new AtfProperties.Workspace(List.of(allowedRoot.toString())));
        return new ProjectAccessPolicy(properties);
    }
}
