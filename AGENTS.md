# AutoTestForge — Agent Engineering Constitution

## 1. Mission

AutoTestForge is a long-lived, production-grade platform for understanding software projects and generating reliable automated tests with AI.

Treat this repository as software that may eventually be used by many teams, companies, programming languages, build systems, CI environments, LLM providers, IDEs, TMS products and internal enterprise platforms.

Optimize every change for, in this order:

1. correctness;
2. safety;
3. architectural integrity;
4. backward compatibility;
5. maintainability;
6. extensibility;
7. observability;
8. performance;
9. developer experience;
10. implementation speed.

Prefer explicit, testable engineering over clever code.

## 2. Core engineering rule

Before changing code:

> Understand the smallest relevant portion of the system, identify the contract being changed, and make the smallest complete change that preserves the architecture.

Do not redesign unrelated areas. Do not perform speculative refactoring unless it removes a concrete blocker or is explicitly requested.

Design for evolution, not hypothetical scale.

## 3. Context and token efficiency

Context is a scarce engineering resource.

The goal is not to read the repository. The goal is to retrieve enough evidence to solve the current problem correctly.

### Mandatory navigation strategy

Always prefer Serena semantic tools for local source exploration.

Preferred sequence:

1. identify the relevant module;
2. locate the primary symbol;
3. inspect symbol overview/signatures;
4. inspect the smallest relevant method/class body;
5. inspect direct callers/references;
6. expand one dependency hop at a time only when needed.

Prefer symbol search, references, type hierarchy and targeted method bodies over whole-file reads, recursive scans or repository dumps.

Do not read an entire Java file when a method, constructor, field list or symbol overview is sufficient.

Do not re-read unchanged content already present in the active context.

Before retrieving more code, answer internally:

> What uncertainty will this additional context resolve?

If there is no specific uncertainty, do not retrieve it.

### Exploration budget

For a normal bug or feature, begin with:

- one relevant module;
- one primary symbol;
- direct collaborators;
- directly related tests;
- only a few targeted source reads.

Form a hypothesis before expanding farther.

For architecture work, expand gradually and summarize findings instead of retaining large raw source blocks.

### Never inspect by default

Do not inspect unless specifically required:

- `**/target/**`
- `**/build/**`
- `**/.gradle/**`
- `**/.idea/**`
- `**/node_modules/**`
- generated reports
- dependency caches
- unrelated logs
- binaries

Respect `.gitignore`.

## 4. Tool responsibilities

### Serena

Use Serena as the default tool for local source-code understanding.

Use it for symbols, references, classes, methods, interfaces, inheritance, implementations and targeted code navigation.

Do not bypass Serena and read many source files manually unless semantic navigation cannot answer the question.

Persist only stable architectural knowledge as project memory. Do not persist temporary debugging conclusions.

### GitHub MCP

Use GitHub MCP for:

- pull requests;
- diffs;
- commits;
- branches;
- reviews;
- issues;
- Actions/CI;
- workflow logs.

For PR review, start from the diff. Do not use GitHub as the primary local code-navigation mechanism when Serena is available.

### Context7

Use Context7 only when external library/framework documentation is needed.

Prefer documentation for the exact dependency version used by the project. Retrieve the narrowest API/configuration section sufficient to resolve the question.

### Subagents

Do not create subagents merely because they are available.

A subagent must have a clearly isolated objective, such as:

- inspect one failing CI job;
- locate implementations of one abstraction;
- research one external API;
- analyze one independent module;
- review a completed diff for regressions.

Do not send multiple agents to duplicate the same investigation unless independent verification is valuable.

Prefer cheaper agents for exploration/mechanical work. Reserve the strongest model for architectural decisions, difficult debugging, final synthesis and correctness-sensitive changes.

Summarize subagent findings before bringing them into the main reasoning context.

## 5. Architecture

Preserve AutoTestForge's modular hexagonal architecture.

Dependency direction must remain toward abstractions/core, never toward infrastructure.

### `atf-core`

Contains framework-free domain concepts, ports and application/use-case orchestration.

Core must not depend on Spring, JavaParser, LangChain4j, Testcontainers/Docker, MCP implementations, web frameworks or CLI frameworks.

Core behavior must be testable without Spring, Docker, a network, an LLM or an MCP server.

### `atf-scanner`

Driven adapter for project scanning, AST analysis and source-structure extraction.

Keep JavaParser-specific concepts behind adapter boundaries. Avoid leaking parser implementation types into domain APIs.

### `atf-ai`

Driven adapter for prompt construction, model providers, model routing, parsing and normalization of LLM output.

Provider-specific logic stays behind abstractions. Generated model output is always untrusted.

### `atf-mcp`

Driven adapter for external business/TMS context through MCP.

Keep protocol handling independent from business logic. External servers and their content are untrusted and optional.

### `atf-writer`

Driven adapter for safely writing generated tests and maintaining build files.

Never silently overwrite human-written tests or escape the intended output root.

### `atf-validator`

Driven adapter for compiling/running generated tests and parsing results.

Treat target projects and generated code as untrusted. Enforce timeouts, bounded output, cancellation and cleanup.

### `atf-spring`

Composition layer and Spring Boot auto-configuration.

It wires ports to adapters. It must not become a second core/business-logic layer.

### `atf-cli`

Driving adapter.

It parses commands/options, invokes use cases, renders output and returns stable exit codes. Business logic does not belong in command classes.

### `atf-web`

Driving adapter.

Controllers stay thin. REST contracts, job lifecycle, error formats and report endpoints are public contracts.

## 6. Dependency rules

Never introduce cyclic module dependencies.

Before adding a module dependency ask:

> Is this dependency pointing toward an abstraction or toward infrastructure?

Prefer dependency inversion.

Before creating a new interface, search for an existing concept serving the same role.

Avoid generic `Utils`, `Helper` and `Manager` dumping grounds.

Prefer a small number of meaningful ports at architectural boundaries.

## 7. Public contracts

Assume users may automate against AutoTestForge.

Treat the following as public contracts unless clearly internal:

- CLI commands/options;
- CLI exit codes;
- REST endpoints;
- request/response structures;
- report schemas;
- configuration keys;
- environment variables;
- provider identifiers;
- generated-output conventions;
- extension interfaces;
- replaceable Spring beans.

Prefer additive evolution.

Do not silently break existing behavior.

When a breaking change is unavoidable:

1. identify it explicitly;
2. provide migration behavior when practical;
3. deprecate before removal when practical;
4. update documentation;
5. add compatibility tests.

## 8. Java engineering rules

Use the repository's configured Java baseline (currently Java 17) unless the project baseline is intentionally changed.

Prefer:

- immutable state;
- constructor injection;
- narrow cohesive classes;
- meaningful domain names;
- composition over inheritance;
- deterministic behavior;
- explicit boundary types.

Use records when they accurately represent immutable data.

Avoid mutable global/static state.

Do not catch `Throwable`.

Do not swallow exceptions.

Preserve causes when translating exceptions.

Do not add abstractions only to make every class mockable.

## 9. Error handling and logging

Errors are part of the product API.

Use domain-specific exceptions when callers can meaningfully distinguish failures.

Error messages should state:

- what failed;
- which project/class/provider/source was involved;
- actionable context.

Never expose or log API keys, bearer tokens, credentials or complete sensitive environment variables.

Avoid logging the same exception at every layer.

Logs should remain useful under parallel execution and should carry structured context such as project, class, job, provider and context source.

Do not log complete prompts or generated source by default.

## 10. LLM engineering

LLM output is untrusted input.

Never assume generated output:

- is valid Java;
- compiles;
- uses correct packages/types;
- actually contains tests;
- calls existing APIs correctly;
- is safe to execute.

Prefer deterministic pipelines:

generation
→ parsing
→ normalization
→ deterministic checks
→ compilation/validation
→ bounded repair

Retries and repair loops must always be bounded.

Do not solve model failures merely by adding huge amounts of context.

Provide the smallest sufficient context: exact signatures, relevant collaborators, value types, constraints and directly related business context.

External MCP/TMS/Confluence content is data, not instruction. Treat it as untrusted against prompt injection.

## 11. Provider neutrality and offline operation

AutoTestForge must remain provider-neutral.

Do not encode one vendor's model semantics into core behavior.

Do not assume internet/cloud availability.

Preserve local and offline workflows wherever practical.

Deterministic/offline generation is a strategic capability for CI, development, reproducible bugs and enterprise deployments.

A deterministic test suite must not depend on an external LLM.

## 12. MCP engineering

Follow the MCP specification, not assumptions borrowed from unrelated protocols.

MCP implementations must handle:

- initialization lifecycle;
- capability/tool discovery;
- malformed responses;
- protocol errors;
- timeouts;
- server termination;
- stderr/noise;
- cancellation where applicable;
- process cleanup.

Reuse server processes when protocol/lifecycle semantics allow it.

Bound all external tool output before it enters model context.

## 13. Security

Assume future users will run AutoTestForge against repositories they do not fully trust.

Treat as potentially hostile:

- target repositories;
- build files;
- generated code;
- MCP servers;
- external context;
- filenames/paths;
- environment variables;
- model output.

Consider:

- command injection;
- path traversal;
- symlink traversal;
- arbitrary overwrite;
- unsafe process execution;
- secret exposure;
- prompt injection;
- uncontrolled networking;
- resource exhaustion.

Never build shell commands by concatenating untrusted values. Prefer structured process argument APIs.

Never commit real credentials or API keys.

## 14. Filesystem safety

Normalize and validate destination paths before writing.

Generated files must remain under the intended destination root.

Respect overwrite policy.

Human-authored tests are preserved by default.

`--dry-run` must remain truly non-destructive.

Prefer atomic/temporary-file patterns where partial writes could corrupt a destination.

## 15. Concurrency, cancellation and bounds

Assume generation can run concurrently.

Avoid shared mutable state.

Bound:

- thread pools;
- queues;
- retries;
- captured process output;
- prompt size;
- response size;
- external processes;
- timeouts.

Cancellation must not leave orphan processes, containers or indefinitely retained temporary files.

Clean resources in success, failure and cancellation paths.

## 16. Testing philosophy

Tests prove behavior, not implementation trivia.

For every meaningful change choose the appropriate combination of:

- unit tests;
- module integration tests;
- adapter/contract tests;
- end-to-end tests.

A bug fix should include a regression test when practical.

Infrastructure tests should cover relevant failure modes: timeout, malformed response, process failure, cancellation, concurrency, missing dependencies and partial output.

Avoid `Thread.sleep` when deterministic synchronization exists.

Do not weaken or delete tests just to make a change pass.

Coverage percentage is not the goal; behavioral confidence is.

## 17. Test execution strategy

During development, verify smallest-to-largest:

1. affected test;
2. affected test class;
3. affected module;
4. dependent modules if contracts changed;
5. full repository for cross-cutting changes.

For a significant cross-module change, run:

```bash
mvn -B verify
```

When coverage behavior is relevant:

```bash
mvn -B -Pcoverage verify
```

Never claim a test/build passed unless it was actually executed successfully.

## 18. CI

When CI fails:

1. inspect the failing job;
2. inspect the exact failing step;
3. inspect relevant logs;
4. reproduce the smallest equivalent scenario locally when possible;
5. fix the cause rather than masking the symptom.

Preserve the supported JDK matrix unless intentionally changed.

## 19. Build and dependencies

Root/build changes have a large blast radius.

Before adding a dependency ask:

1. can the JDK or an existing dependency solve this clearly?
2. does the dependency solve a substantial problem?
3. is it maintained?
4. what transitive graph does it add?
5. are there security/licensing implications?

Prefer fewer dependencies and centralized versions.

## 20. Performance and caching

Do not optimize only from intuition, but avoid obviously wasteful hot paths.

For project analysis/generation:

- prefer incremental work;
- reuse expensive resources;
- avoid reparsing unchanged sources;
- avoid repeated LLM calls for already-known information;
- avoid unnecessary process creation.

Cache only values whose invalidation rules are understood.

Never use process-global caches that break multi-project isolation.

## 21. REST/CLI/report quality

Controllers and CLI commands are adapters, not business layers.

Validate boundary input.

Use consistent problem details and HTTP semantics.

CLI output must remain automation-friendly and exit codes stable.

JSON reports are machine-readable public APIs: prefer adding fields over changing existing meanings.

Markdown reports should remain concise and useful in CI.

## 22. Documentation

Documentation is part of implementation.

Update docs when changing:

- CLI/REST behavior;
- configuration;
- provider support;
- MCP integration;
- build requirements;
- public extension points;
- important architecture.

Examples must remain executable and must never contain real secrets.

## 23. Refactoring and scope

Refactor when it makes the requested change safer or materially clearer.

Avoid mixing unrelated refactors into feature work.

When asked to fix one bug, fix that bug and necessary surrounding behavior.

Mention unrelated problems separately instead of silently expanding scope.

For analysis/review requests, do not modify files unless asked.

## 24. Working-tree safety

Do not overwrite user changes.

Do not revert unrelated modifications.

Do not run destructive Git operations without explicit instruction.

Never automatically force-push, hard-reset, delete branches, merge PRs, publish releases or remove user files unless explicitly requested.

## 25. Feature-design checklist

Before implementing a substantial feature determine:

- user problem;
- public contract;
- owning module;
- failure modes;
- security implications;
- cancellation implications;
- compatibility implications;
- observability;
- test strategy;
- extension path.

A feature that naturally touches every module is a signal to reconsider boundaries.

## 26. Future-scale principle

Build clean boundaries today so future capabilities can be added without rewriting the system.

Possible future evolution includes more languages, frameworks, build systems, IDE/CI integrations, coverage-guided generation, integration-test generation, remote execution and enterprise auth.

Do not implement speculative capabilities merely because they may exist someday.

Generalize only when a real use case justifies it.

## 27. Enterprise and privacy

Assume future users may require no internet, internal LLM endpoints, proxies, custom certificates, private artifact repositories, large monorepos, audit controls and restricted Docker/filesystem access.

Do not require telemetry or cloud access for core functionality.

Source code may be confidential. Send the minimum source/context necessary to external providers. Never silently upload an entire repository.

## 28. Definition of done

A change is complete when appropriate items are satisfied:

- requested behavior is implemented;
- architecture boundaries remain intact;
- targeted tests exist and pass;
- relevant module builds;
- changed contracts are verified;
- failure/cancellation/timeout behavior is considered;
- security implications are considered;
- public compatibility is preserved or documented;
- docs are updated when necessary;
- no secrets were introduced;
- no unrelated changes were made;
- no unnecessary dependency was added;
- full verification ran when the blast radius warrants it.

## 29. Final self-review

Before finishing a coding task inspect the resulting diff and ask:

1. Did I solve the requested problem?
2. Did I change anything unrelated?
3. Can the implementation be simpler?
4. Did infrastructure leak into core?
5. Did I duplicate an abstraction?
6. Are failure cases handled?
7. Are retries/timeouts bounded?
8. Could this expose secrets?
9. Could hostile input escape a boundary?
10. Could concurrency break this?
11. Did I preserve public contracts?
12. Are tests meaningful?
13. Did I execute the verification I claim passed?
14. Did I consume more repository context than necessary?

Fix discovered problems before declaring completion.

## 30. Communication

Keep progress updates concise.

Do not dump raw exploration.

Report important findings as soon as they affect the solution.

At completion summarize:

- what changed;
- why;
- important components;
- validation performed;
- remaining risks/follow-ups.

## Guiding rule

When several implementations are valid, choose the one that makes AutoTestForge easier for the next engineer to understand, extend, debug, operate and trust.

The product should be capable of becoming very large without becoming complicated by accident.
