package com.autotestforge.scanner;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.FieldDependency;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.ParameterInfo;
import com.autotestforge.core.domain.ScannedProject;
import com.autotestforge.core.exception.ScanException;
import com.autotestforge.core.port.out.ProjectScannerPort;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@link ProjectScannerPort} adapter built on JavaParser.
 * <p>
 * Recursively locates every {@code src/main/java} source root (multi-module
 * aware), parses each compilation unit with symbol resolution enabled and
 * extracts the metadata the AI generator needs: public API, Javadoc,
 * annotations, collaborators and Spring stereotypes.
 */
public class JavaParserProjectScanner implements ProjectScannerPort {

    private static final Logger log = LoggerFactory.getLogger(JavaParserProjectScanner.class);

    private static final Path SOURCE_ROOT_SUFFIX = Path.of("src", "main", "java");
    private static final Set<String> SKIPPED_DIRS =
            Set.of("target", "build", "out", ".git", ".idea", ".gradle", "node_modules");
    private static final Set<String> SPRING_STEREOTYPES = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController",
            "Configuration", "ControllerAdvice", "RestControllerAdvice");
    private static final Set<String> INJECTION_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource");
    /** Value-like types that never make sense to mock. */
    private static final Set<String> NON_COLLABORATOR_TYPES = Set.of(
            "String", "Integer", "Long", "Double", "Float", "Short", "Byte", "Boolean", "Character",
            "BigDecimal", "BigInteger", "LocalDate", "LocalDateTime", "LocalTime", "Instant", "Duration",
            "UUID", "Object", "List", "Set", "Map", "Collection", "Optional");

    @Override
    public ScannedProject scan(Path projectRoot) {
        if (!Files.isDirectory(projectRoot)) {
            throw new ScanException("Project root does not exist or is not a directory: " + projectRoot);
        }
        List<Path> sourceRoots = findSourceRoots(projectRoot);
        if (sourceRoots.isEmpty()) {
            throw new ScanException("No src/main/java source roots found under " + projectRoot);
        }
        log.info("Found {} source root(s) under {}", sourceRoots.size(), projectRoot);

        JavaParser parser = createParser(sourceRoots);
        List<JavaClassInfo> classes = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            classes.addAll(scanSourceRoot(parser, sourceRoot));
        }
        log.info("Scanned {} top-level types", classes.size());
        return new ScannedProject(projectRoot, List.copyOf(classes), DependencyGraphBuilder.build(classes));
    }

    /** Multi-module aware: any nested {@code src/main/java} directory is a source root. */
    private List<Path> findSourceRoots(Path projectRoot) {
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            return paths
                    .filter(Files::isDirectory)
                    .filter(path -> path.endsWith(SOURCE_ROOT_SUFFIX))
                    .filter(path -> !isInsideSkippedDir(projectRoot, path))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new ScanException("Failed to walk project directory " + projectRoot, e);
        }
    }

    private boolean isInsideSkippedDir(Path projectRoot, Path path) {
        Path relative = projectRoot.relativize(path);
        for (Path segment : relative) {
            if (SKIPPED_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Symbol resolution combines the JDK (reflection) with all project source
     * roots, so cross-module types resolve to fully qualified names.
     */
    private JavaParser createParser(List<Path> sourceRoots) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        sourceRoots.forEach(root -> typeSolver.add(new JavaParserTypeSolver(root)));

        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        return new JavaParser(configuration);
    }

    private List<JavaClassInfo> scanSourceRoot(JavaParser parser, Path sourceRoot) {
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return !name.equals("package-info.java") && !name.equals("module-info.java");
                    })
                    .sorted()
                    .flatMap(file -> parseFile(parser, file).stream())
                    .toList();
        } catch (IOException e) {
            throw new ScanException("Failed to walk source root " + sourceRoot, e);
        }
    }

    /** A single unreadable file is logged and skipped; it must not fail the scan. */
    private List<JavaClassInfo> parseFile(JavaParser parser, Path file) {
        try {
            String source = Files.readString(file);
            ParseResult<CompilationUnit> result = parser.parse(source);
            Optional<CompilationUnit> unit = result.getResult();
            if (!result.isSuccessful() || unit.isEmpty()) {
                log.warn("Skipping {} - parse problems: {}", file, result.getProblems());
                return List.of();
            }
            return extractTypes(unit.get(), source, file);
        } catch (IOException e) {
            log.warn("Skipping unreadable file {}", file, e);
            return List.of();
        }
    }

    private List<JavaClassInfo> extractTypes(CompilationUnit unit, String source, Path file) {
        String packageName = unit.getPackageDeclaration()
                .map(pkg -> pkg.getNameAsString())
                .orElse("");
        List<String> imports = unit.getImports().stream()
                .map(imp -> imp.getNameAsString() + (imp.isAsterisk() ? ".*" : ""))
                .toList();

        List<JavaClassInfo> types = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.getTypes()) {
            toClassInfo(type, packageName, source, file, imports).ifPresent(types::add);
        }
        return types;
    }

    private Optional<JavaClassInfo> toClassInfo(TypeDeclaration<?> type,
                                                String packageName,
                                                String source,
                                                Path file,
                                                List<String> imports) {
        ClassKind kind;
        boolean isAbstract = false;
        if (type instanceof ClassOrInterfaceDeclaration classOrInterface) {
            kind = classOrInterface.isInterface() ? ClassKind.INTERFACE : ClassKind.CLASS;
            isAbstract = classOrInterface.isAbstract();
        } else if (type instanceof EnumDeclaration) {
            kind = ClassKind.ENUM;
        } else if (type instanceof RecordDeclaration) {
            kind = ClassKind.RECORD;
        } else {
            return Optional.empty();   // annotations and other declarations are not test targets
        }

        List<String> annotations = annotationNames(type.getAnnotations());
        return Optional.of(new JavaClassInfo(
                packageName,
                type.getNameAsString(),
                kind,
                isAbstract,
                source,
                JavadocExtractor.extract(type),
                annotations,
                extractPublicMethods(type),
                extractDependencies(type),
                imports,
                file.toAbsolutePath(),
                annotations.stream().filter(SPRING_STEREOTYPES::contains).findFirst().orElse(null)));
    }

    private List<MethodInfo> extractPublicMethods(TypeDeclaration<?> type) {
        boolean isInterface = type instanceof ClassOrInterfaceDeclaration cid && cid.isInterface();
        return type.getMethods().stream()
                .filter(method -> method.isPublic() || (isInterface && !method.isPrivate()))
                .map(this::toMethodInfo)
                .toList();
    }

    private MethodInfo toMethodInfo(MethodDeclaration method) {
        List<ParameterInfo> parameters = method.getParameters().stream()
                .map(param -> new ParameterInfo(param.getNameAsString(), describeType(param.getType())))
                .toList();
        List<String> thrownExceptions = method.getThrownExceptions().stream()
                .map(Type::asString)
                .toList();
        return new MethodInfo(
                method.getNameAsString(),
                describeType(method.getType()),
                parameters,
                thrownExceptions,
                JavadocExtractor.extract(method),
                annotationNames(method.getAnnotations()),
                method.isStatic());
    }

    /**
     * Collaborators = instance fields plus record components, excluding value
     * types. These are the mock candidates for the generated tests.
     */
    private List<FieldDependency> extractDependencies(TypeDeclaration<?> type) {
        List<FieldDependency> dependencies = new ArrayList<>();

        for (FieldDeclaration field : type.getFields()) {
            if (field.isStatic()) {
                continue;
            }
            String fieldType = describeType(field.getElementType());
            if (!isCollaboratorType(field.getElementType(), fieldType)) {
                continue;
            }
            boolean injected = field.getAnnotations().stream()
                    .map(AnnotationExpr::getNameAsString)
                    .anyMatch(INJECTION_ANNOTATIONS::contains)
                    || (field.isFinal() && field.getVariables().stream()
                            .allMatch(variable -> variable.getInitializer().isEmpty()));
            field.getVariables().forEach(variable ->
                    dependencies.add(new FieldDependency(variable.getNameAsString(), fieldType, injected)));
        }

        if (type instanceof RecordDeclaration record) {
            record.getParameters().forEach(component -> {
                String componentType = describeType(component.getType());
                if (isCollaboratorType(component.getType(), componentType)) {
                    dependencies.add(new FieldDependency(component.getNameAsString(), componentType, true));
                }
            });
        }
        return dependencies;
    }

    private boolean isCollaboratorType(Type type, String describedType) {
        if (type.isPrimitiveType() || type.isArrayType()) {
            return false;
        }
        String raw = describedType;
        int genericStart = raw.indexOf('<');
        if (genericStart > 0) {
            raw = raw.substring(0, genericStart);
        }
        String simpleName = raw.substring(raw.lastIndexOf('.') + 1);
        return !raw.startsWith("java.") && !raw.startsWith("javax.")
                && !NON_COLLABORATOR_TYPES.contains(simpleName);
    }

    /** Prefers the fully qualified name; falls back to the source-level name when unresolvable. */
    private String describeType(Type type) {
        try {
            return type.resolve().describe();
        } catch (RuntimeException e) {
            return type.asString();
        }
    }

    private List<String> annotationNames(List<AnnotationExpr> annotations) {
        return annotations.stream().map(AnnotationExpr::getNameAsString).toList();
    }
}
