package com.autotestforge.core.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * One discovered type as shown in a scan preview: whether the pipeline would
 * generate tests for it and why not otherwise.
 *
 * @param classFqn          fully qualified name
 * @param kind              class / interface / enum / record
 * @param springStereotype  Spring stereotype or null
 * @param publicMethods     public method names
 * @param collaborators     types of the collaborators that would be mocked
 * @param sourceFile        absolute path of the source file
 * @param eligible          true when the current request would generate a test for this type
 * @param skipReason        why the type is not eligible, null when eligible
 * @param existingTest      conventional test file when one already exists, else null
 */
public record ClassTarget(
        String classFqn,
        ClassKind kind,
        String springStereotype,
        List<String> publicMethods,
        List<String> collaborators,
        Path sourceFile,
        boolean eligible,
        String skipReason,
        Path existingTest) {

    public ClassTarget {
        publicMethods = publicMethods == null ? List.of() : List.copyOf(publicMethods);
        collaborators = collaborators == null ? List.of() : List.copyOf(collaborators);
    }

    public boolean hasExistingTest() {
        return existingTest != null;
    }
}
