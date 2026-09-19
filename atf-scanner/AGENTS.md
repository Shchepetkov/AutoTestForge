# atf-scanner — Agent Rules

These rules extend the repository-level `AGENTS.md`.

## Role

`atf-scanner` is the Java source-analysis adapter. It recursively understands target projects and uses JavaParser/symbol solving to produce the structural information required by core.

## Boundary rules

JavaParser is an implementation detail of this adapter.

Do not leak `CompilationUnit`, JavaParser AST nodes, resolved declarations or symbol-solver implementation types into core/public domain contracts unless the architecture explicitly chooses them as a public API.

Convert parser details into stable AutoTestForge models at the adapter boundary.

## Analysis correctness

Source analysis must degrade gracefully when projects are incomplete.

Expect:

- uncompilable source;
- missing dependencies;
- unresolved symbols;
- generated code;
- multi-module projects;
- unusual source roots;
- nested/inner classes;
- records/enums/interfaces;
- overloaded/generic methods.

Do not make repository scanning fail globally because one source file is malformed when useful partial results can safely be returned.

Differentiate "not found", "unsupported/unresolved" and genuine scanner failure where that distinction matters.

## Performance

Scanning can become a repository-scale hot path.

Prefer:

- one parse/index pass reused across related queries;
- bounded traversal;
- incremental/targeted resolution;
- avoidance of reparsing identical files;
- deterministic ordering of results.

Do not recursively walk ignored build/cache directories.

Respect project root and source-root boundaries.

## Paths and security

Target repositories are untrusted.

Normalize paths and ensure traversal stays inside the intended project root.

Handle symlinks carefully.

Never execute target-project code merely to scan source.

## Dependency graph/context extraction

Generation context must include only information that materially helps test generation.

Prefer signatures, relevant collaborators and value-type structure over entire source bodies.

Avoid growing prompts simply because more AST data is available.

## Testing

Use small fixture projects covering:

- Maven and relevant Gradle layouts;
- nested/multi-module structures;
- unresolved symbols;
- malformed files;
- overloaded/generic APIs;
- configuration classes that should be skipped;
- dependency relationships;
- ignored directories.

Tests should be deterministic and network-free.

## Context strategy

Use Serena to inspect the scanner symbol responsible for the behavior, then its direct parser/model collaborators.

Avoid loading large fixture trees unless the failing test specifically depends on them.

## Completion check

Verify:

- no JavaParser implementation type leaked into core unintentionally;
- scanning remains bounded and deterministic;
- malformed/partial projects fail gracefully;
- relevant scanner tests pass.
