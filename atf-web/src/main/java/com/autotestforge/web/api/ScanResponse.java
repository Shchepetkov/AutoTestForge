package com.autotestforge.web.api;

import com.autotestforge.core.domain.ClassTarget;
import com.autotestforge.core.domain.ScanPreview;

import java.util.List;

/** Result of {@code POST /api/scan}. */
public record ScanResponse(
        String projectPath,
        String buildTool,
        int discovered,
        long eligible,
        long withExistingTests,
        List<Entry> classes) {

    public static ScanResponse from(ScanPreview preview) {
        return new ScanResponse(
                preview.projectPath().toString(),
                preview.buildTool() == null ? null : preview.buildTool().name(),
                preview.classes().size(),
                preview.eligibleCount(),
                preview.withExistingTests(),
                preview.classes().stream().map(Entry::from).toList());
    }

    public record Entry(
            String classFqn,
            String kind,
            String springStereotype,
            List<String> publicMethods,
            List<String> collaborators,
            String sourceFile,
            boolean eligible,
            String skipReason,
            String existingTest) {

        static Entry from(ClassTarget target) {
            return new Entry(
                    target.classFqn(),
                    target.kind().name(),
                    target.springStereotype(),
                    target.publicMethods(),
                    target.collaborators(),
                    target.sourceFile() == null ? null : target.sourceFile().toString(),
                    target.eligible(),
                    target.skipReason(),
                    target.existingTest() == null ? null : target.existingTest().toString());
        }
    }
}
