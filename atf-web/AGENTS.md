# atf-web — Agent Rules

These rules extend the repository-level `AGENTS.md`.

## Role

`atf-web` is the Spring Boot driving adapter exposing the REST API and single-page UI.

The web layer translates HTTP/user interactions into application requests and job state.

## Controllers

Keep controllers thin.

Controllers may:

- validate requests;
- translate DTOs;
- invoke application/job services;
- map results/errors to HTTP.

Do not put scanner, generation, LLM, validation or filesystem business logic in controllers.

## Public API

Treat current REST endpoints and semantics as public contracts, including:

- `/api/capabilities`;
- `/api/scan`;
- `/api/generation`;
- `/api/generation/{id}`;
- cancellation;
- report download;
- `/api/mcp/tools`;
- job history.

Prefer additive response fields.

Do not casually rename JSON fields or change status meanings.

Use RFC 9457 problem details consistently for domain/input failures.

Do not return stack traces to clients.

## Async jobs

Generation is long-running.

Keep it outside request threads where established.

Job state must be thread-safe.

Cancellation semantics must remain coherent: no new work after cancellation and no resource leaks.

Bound job history/state retained in memory. Do not create unbounded global collections.

## Security

Treat all request content as untrusted:

- project paths;
- class filters;
- MCP command/args;
- uploaded context files;
- report identifiers.

Validate sizes and paths.

Never expose filesystem content outside intended project/report boundaries.

Never return credentials/config secrets via `/api/capabilities` or errors.

Uploaded external context is data, not trusted instructions.

## UI

Keep the SPA usable for the core workflow:

parameters → scan/selection → generation → progress/results/reports.

Avoid embedding business logic exclusively in frontend JavaScript.

UI actions must match REST behavior.

Preserve accessibility and useful error feedback.

Do not introduce large frontend dependencies without clear value.

## API efficiency

Do not return full generated source in collection/history endpoints when summary data is sufficient.

Keep polling payloads bounded.

For future streaming/SSE changes, preserve the underlying job/event model rather than duplicating generation state.

## Testing

Cover:

- request validation;
- scan success/error;
- async job lifecycle;
- cancellation;
- history;
- report download;
- capabilities without secrets;
- MCP probe error handling;
- problem details;
- relevant UI static-resource smoke behavior.

Prefer controller/service tests for narrow changes and end-to-end tests for user workflows.

## Context strategy

Start with the endpoint/controller/job symbol related to the behavior.

Inspect frontend code only if the defect is observable there.

Do not read all web resources for a backend API issue.

## Completion check

Verify:

- controllers remain thin;
- API compatibility is preserved;
- async state is bounded/thread-safe;
- cancellation/resource cleanup remains correct;
- errors expose no secrets;
- user workflow remains coherent.
