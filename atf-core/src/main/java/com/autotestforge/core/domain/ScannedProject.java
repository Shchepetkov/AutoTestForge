package com.autotestforge.core.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of scanning a target project: all discovered types plus the
 * dependency graph between them.
 */
public record ScannedProject(Path projectRoot, List<JavaClassInfo> classes, DependencyGraph dependencyGraph) {
}
