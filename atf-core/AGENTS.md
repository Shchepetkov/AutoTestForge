# atf-core — Agent Rules

These rules extend the repository-level `AGENTS.md`.

## Role

`atf-core` is the framework-free center of the hexagon: domain model, ports and application/use-case orchestration.

Its strongest property is independence from infrastructure.

## Hard boundaries

Do not add dependencies from `atf-core` to:

- Spring/Spring Boot;
- JavaParser;
- LangChain4j or model SDKs;
- MCP protocol implementations;
- Docker/Testcontainers;
- Picocli;
- HTTP/web frameworks;
- persistence clients.

SLF4J is acceptable for logging, but core behavior must not depend on logging side effects.

If a use case needs infrastructure, define or reuse a meaningful port in core and implement it in an adapter module.

## Design rules

- Keep use cases explicit and cohesive.
- Keep ports narrow and capability-oriented.
- Prefer immutable domain values.
- Avoid infrastructure-shaped DTOs in domain APIs.
- Do not encode provider-specific or JavaParser-specific concepts into core contracts.
- Do not create an interface for every class; create ports only at real architectural boundaries.
- Keep orchestration separate from technology details.
- Avoid static/global mutable state.

## Cross-module evolution

When changing a port:

1. find every implementation with Serena;
2. find every direct caller;
3. assess whether it is a public extension point;
4. prefer additive evolution;
5. update contract tests/adapter tests where appropriate.

Do not change a port signature casually because it can affect most adapters.

## Error model

Use domain/application exceptions to express failures that callers can reason about.

Do not leak raw provider, parser, process or HTTP exceptions through core contracts unless deliberately wrapped as a stable cause.

Preserve useful diagnostic context without exposing secrets.

## Concurrency and cancellation

Core orchestration may run generation in parallel.

Do not introduce shared mutable state between jobs/classes.

Cancellation should be represented as application behavior, not tied to a specific thread/executor implementation.

Keep orchestration deterministic where practical.

## Testing

Core tests should run:

- without Spring;
- without Docker;
- without network access;
- without a real LLM;
- without a real MCP server.

Mock/stub ports at the boundary and assert meaningful orchestration outcomes rather than internal call trivia.

For any workflow change, cover success plus the most important failure/cancellation path.

## Context strategy

Start with the affected use case or domain type.

Use Serena references to identify ports/callers.

Do not inspect adapter implementations unless required to understand a changed contract.

## Completion check

Before finishing a core change verify:

- no infrastructure dependency leaked inward;
- ports remain minimal;
- affected adapters still compile against changed contracts;
- orchestration tests prove behavior;
- no hidden shared state was introduced.
