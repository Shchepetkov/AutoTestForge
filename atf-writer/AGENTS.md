# atf-writer — Agent Rules

These rules extend the repository-level `AGENTS.md`.

## Role

`atf-writer` safely materializes generated tests into a target project and performs required build-file maintenance.

This module has direct write access to user repositories, so safety outranks convenience.

## Filesystem safety

All destination paths must be normalized and validated against the intended project/output root.

Protect against:

- `..` traversal;
- absolute-path escape;
- symlink escape;
- malformed package-to-path conversion;
- accidental overwrite.

Do not write outside the intended root.

Prefer temporary/atomic replacement patterns when an interrupted write could corrupt a file.

## Existing tests

Human-authored tests are preserved by default.

Never silently overwrite an existing test.

Respect:

- default skip behavior;
- explicit `--overwrite`;
- `--dry-run`;
- `--output-dir`.

A dry run must not mutate the target repository or its build files.

## Build-file modification

Build files are user-owned configuration.

Make minimal idempotent edits.

Before adding dependencies/plugins/configuration:

- detect existing equivalent configuration;
- preserve unrelated formatting/behavior as far as practical;
- avoid duplicates;
- preserve Maven/Gradle semantics.

For Maven changes use the Maven model where appropriate rather than brittle text replacement.

Do not assume every target project has the same layout.

## Multi-module projects

Write tests into the owning module.

Do not accidentally write all generated tests to the repository root.

Resolve source/test roots deliberately.

## Security

Never execute target-project code in this module merely to decide where to write.

Treat project paths/package names as untrusted input.

Do not print secrets encountered in build files.

## Testing

Use temporary directories/fixture projects.

Cover:

- fresh write;
- existing test skip;
- overwrite;
- dry-run non-mutation;
- output-dir;
- nested modules;
- invalid/escaping paths;
- idempotent build-file update;
- existing equivalent dependency/plugin;
- failure midway through writing.

Tests must clean their temp files.

## Context strategy

Inspect the specific writer/build-file symbol and its tests.

Do not read whole target fixture projects when only one POM/path scenario matters.

## Completion check

Verify:

- no path can escape its root;
- existing user tests remain safe;
- build-file edits are minimal/idempotent;
- dry-run is non-destructive;
- module-aware placement is preserved.
