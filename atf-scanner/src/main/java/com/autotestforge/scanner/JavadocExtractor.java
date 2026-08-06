package com.autotestforge.scanner;

import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;

/** Extracts normalized Javadoc text from AST nodes; the LLM uses it to infer intended behavior. */
final class JavadocExtractor {

    private JavadocExtractor() {
    }

    static String extract(NodeWithJavadoc<?> node) {
        return node.getJavadoc()
                .map(javadoc -> javadoc.toText().strip())
                .orElse("");
    }
}
