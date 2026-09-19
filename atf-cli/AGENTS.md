# atf-cli — Agent Rules

These rules extend the repository-level `AGENTS.md`.

## Role

`atf-cli` is the Spring Boot + Picocli driving adapter.

It converts command-line intent into application-use-case calls and stable process output/exit codes.

## Thin-adapter rule

Command classes may:

- declare options;
- validate command-line input;
- translate options to application requests;
- call use cases;
- format output;
- select exit codes.

They must not implement scanning, generation, validation, provider or filesystem business logic.

Move reusable behavior behind application/core services.

## CLI contracts

Treat commands, option names, aliases, defaults and exit codes as public APIs.

Preserve established behavior for:

- `scan`;
- `generate`;
- `--classes`;
- `--exclude`;
- `--overwrite`;
- `--parallelism`;
- `--output-dir`;
- `--dry-run`;
- `--print`;
- report flags;
- context/MCP flags;
- `--atf.*` overrides.

Prefer additive options.

Avoid renaming/removing flags without a migration/deprecation path.

## Automation friendliness

Do not introduce mandatory interactive prompts.

Keep machine-usable output stable.

Do not mix large diagnostics into stdout when stdout is being used for requested generated/report content.

Exit codes must correspond to documented outcomes.

## Input handling

Reject impossible combinations early with actionable messages.

Normalize paths safely.

Do not reinterpret arbitrary command-line text as shell commands.

## Testing

Cover:

- parsing;
- defaults;
- invalid options;
- exit codes;
- dry-run;
- scan/generate command routing;
- `--atf.*` override compatibility;
- report/output flags;
- error rendering.

Prefer command/application boundary tests over full expensive generation when testing Picocli semantics.

## Context strategy

Start with the relevant command and its test.

Inspect the corresponding core request/use case only if option translation or contract behavior is involved.

Do not inspect all generation adapters for a CLI-only parsing bug.

## Completion check

Verify:

- command class remains thin;
- existing scripts remain compatible;
- stdout/stderr semantics are sensible;
- exit code behavior is covered;
- new flags are documented.
