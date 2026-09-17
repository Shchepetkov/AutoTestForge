package com.autotestforge.web.api;

import com.autotestforge.web.project.ProjectInspectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Compact project inspection endpoints for UIs and non-MCP clients. */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectInspectionService inspectionService;

    public ProjectController(ProjectInspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @PostMapping("/inspect")
    public ProjectInspectionService.ProjectSummary inspect(@Valid @RequestBody ProjectRequest request) {
        return inspectionService.inspect(request.projectPath());
    }

    @PostMapping("/class-context")
    public ProjectInspectionService.ClassContext classContext(@Valid @RequestBody ClassContextRequest request) {
        return inspectionService.classContext(request.projectPath(), request.className(),
                request.includeSource(), request.maxSourceChars());
    }

    public record ProjectRequest(@NotBlank String projectPath) {
    }

    public record ClassContextRequest(@NotBlank String projectPath, @NotBlank String className,
                                      boolean includeSource, int maxSourceChars) {
    }
}
