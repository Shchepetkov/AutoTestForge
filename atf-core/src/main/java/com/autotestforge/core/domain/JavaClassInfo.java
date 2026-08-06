package com.autotestforge.core.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything the pipeline knows about one scanned top-level Java type.
 * Immutable snapshot produced by the scanner and consumed by the AI generator
 * and the test writer.
 *
 * @param packageName      package the type is declared in
 * @param className        simple name of the type
 * @param kind             class/interface/enum/record
 * @param isAbstract       whether the type is abstract
 * @param sourceCode       full source of the compilation unit
 * @param javadoc          type-level Javadoc, empty string when absent
 * @param annotations      simple names of the annotations on the type
 * @param publicMethods    public API of the type
 * @param dependencies     collaborators (fields / constructor parameters)
 * @param imports          import statements of the compilation unit
 * @param sourceFile       absolute path of the {@code .java} file
 * @param springStereotype Spring stereotype (e.g. {@code Service}, {@code RestController}), null for plain classes
 */
public record JavaClassInfo(
        String packageName,
        String className,
        ClassKind kind,
        boolean isAbstract,
        String sourceCode,
        String javadoc,
        List<String> annotations,
        List<MethodInfo> publicMethods,
        List<FieldDependency> dependencies,
        List<String> imports,
        Path sourceFile,
        String springStereotype) {

    public String fullyQualifiedName() {
        return packageName == null || packageName.isBlank()
                ? className
                : packageName + "." + className;
    }

    public boolean isSpringComponent() {
        return springStereotype != null;
    }
}
