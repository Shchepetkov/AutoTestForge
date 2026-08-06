package com.autotestforge.core.port.out;

import com.autotestforge.core.domain.ScannedProject;
import com.autotestforge.core.exception.ScanException;

import java.nio.file.Path;

/** Driven port: discovers and analyzes all Java types of a target project. */
public interface ProjectScannerPort {

    /**
     * Recursively scans {@code projectRoot}, parses every production source file
     * and builds the intra-project dependency graph.
     *
     * @throws ScanException when the project cannot be read or contains no sources
     */
    ScannedProject scan(Path projectRoot);
}
