package com.autotestforge.web.project;

import com.autotestforge.core.domain.DependencyGraph;
import com.autotestforge.core.domain.FieldDependency;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.ScannedProject;
import com.autotestforge.core.port.out.ProjectScannerPort;
import com.autotestforge.web.security.ProjectAccessPolicy;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Token-efficient read API used by REST and MCP tools. */
@Service
public class ProjectInspectionService {

    private final ProjectScannerPort scanner;
    private final ProjectAccessPolicy accessPolicy;

    public ProjectInspectionService(ProjectScannerPort scanner, ProjectAccessPolicy accessPolicy) {
        this.scanner = scanner;
        this.accessPolicy = accessPolicy;
    }

    public ProjectSummary inspect(String projectPath) {
        Path root = accessPolicy.requireAllowedProject(projectPath);
        ScannedProject project = scanner.scan(root);
        List<ClassSummary> classes = project.classes().stream()
                .sorted(Comparator.comparing(JavaClassInfo::fullyQualifiedName))
                .map(info -> new ClassSummary(info.fullyQualifiedName(), info.kind().name(), info.isAbstract(),
                        info.springStereotype(), info.publicMethods().size(), info.dependencies().size()))
                .toList();
        int dependencyEdges = project.dependencyGraph().classes().stream()
                .mapToInt(name -> project.dependencyGraph().dependenciesOf(name).size())
                .sum();
        return new ProjectSummary(root.toString(), classes.size(), dependencyEdges, classes);
    }

    public ClassContext classContext(String projectPath, String className,
                                     boolean includeSource, int maxSourceChars) {
        Path root = accessPolicy.requireAllowedProject(projectPath);
        ScannedProject project = scanner.scan(root);
        JavaClassInfo info = project.classes().stream()
                .filter(candidate -> candidate.className().equals(className)
                        || candidate.fullyQualifiedName().equals(className))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Class not found: " + className));
        DependencyGraph graph = project.dependencyGraph();
        String source = includeSource ? trim(info.sourceCode(), normalizeMaxChars(maxSourceChars)) : null;
        return new ClassContext(
                info.fullyQualifiedName(), info.kind().name(), info.isAbstract(), info.springStereotype(),
                info.javadoc(), info.annotations(),
                info.publicMethods().stream().map(ProjectInspectionService::methodSummary).toList(),
                info.dependencies(),
                graph.dependenciesOf(info.fullyQualifiedName()).stream().sorted().toList(),
                graph.dependentsOf(info.fullyQualifiedName()).stream().sorted().toList(),
                root.relativize(info.sourceFile()).toString(), source);
    }

    private static String methodSummary(MethodInfo method) {
        return method.signature() + (method.javadoc().isBlank() ? "" : " — " + method.javadoc().replace('\n', ' ').strip());
    }

    private static int normalizeMaxChars(int value) {
        return value <= 0 ? 12_000 : Math.min(value, 50_000);
    }

    private static String trim(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "\n... [truncated]";
    }

    public record ProjectSummary(String projectPath, int classCount, int dependencyEdges,
                                 List<ClassSummary> classes) {
    }

    public record ClassSummary(String fullyQualifiedName, String kind, boolean abstractType,
                               String springStereotype, int publicMethodCount, int collaboratorCount) {
    }

    public record ClassContext(String fullyQualifiedName, String kind, boolean abstractType,
                               String springStereotype, String javadoc, List<String> annotations,
                               List<String> publicMethods, List<FieldDependency> collaborators,
                               List<String> dependencies, List<String> dependents,
                               String sourceFile, String sourceCode) {
    }
}
