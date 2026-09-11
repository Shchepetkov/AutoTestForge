package com.autotestforge.core.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of a scan-only run: every discovered type with its eligibility for
 * test generation under the given request filters.
 *
 * @param projectPath root of the scanned project
 * @param buildTool   detected build tool, null when no supported build file was found
 * @param classes     discovered types in scan order
 */
public record ScanPreview(Path projectPath, BuildTool buildTool, List<ClassTarget> classes) {

    public ScanPreview {
        classes = classes == null ? List.of() : List.copyOf(classes);
    }

    public long eligibleCount() {
        return classes.stream().filter(ClassTarget::eligible).count();
    }

    public long withExistingTests() {
        return classes.stream().filter(target -> target.eligible() && target.hasExistingTest()).count();
    }
}
