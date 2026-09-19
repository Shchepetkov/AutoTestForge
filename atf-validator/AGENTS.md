# atf-validator — Agent Rules

These rules extend the repository-level `AGENTS.md`.

## Role

`atf-validator` executes generated tests, preferably in isolation, and converts build/test results into stable validation outcomes.

Generated code and target projects are untrusted executable input.

## Isolation

Prefer Docker/Testcontainers when available and configured.

When falling back to local execution, preserve explicit safety boundaries and never imply equivalent isolation.

Do not execute more of the target project than necessary to validate the generated test.

## Process execution

Every process must have:

- finite timeout;
- bounded captured output;
- stdout/stderr draining that cannot deadlock;
- termination on timeout/cancellation;
- cleanup;
- useful exit-code/diagnostic handling.

Do not call blocking full-stream reads that make timeout enforcement ineffective.

Kill process trees/containers where needed, not just parent handles.

## Output bounds

Build tools can emit enormous logs.

Keep bounded diagnostic head/tail or another bounded strategy.

Return enough context to repair a failure without sending megabytes of logs to an LLM.

Do not include credentials from environment/build logs.

## Docker availability

Docker is optional.

Do not produce noisy stack traces merely probing Docker on machines where Docker is clearly unavailable.

Distinguish:

- Docker unavailable;
- Docker misconfigured;
- image/pull/runtime failure;
- target-build failure.

Fallback behavior must be explicit and tested.

## Build systems

Keep Maven and Gradle handling separate enough to preserve their semantics.

Do not assume one fixed command works for all multi-module projects.

Run validation in the owning module/context when appropriate.

## Cancellation

Cancellation must stop launching new work and terminate current external work when the contract requires it.

Do not leave containers/processes behind.

## Testing

Use controlled scripts/fake projects for process behavior.

Cover:

- successful validation;
- compilation/test failure;
- hanging process timeout;
- very large stdout/stderr;
- cancellation;
- unavailable Docker;
- local fallback;
- cleanup;
- multi-module command scoping.

Avoid flaky timing tests. Use generous deterministic synchronization and bounded waits.

## Context strategy

When debugging validation, start from the exact execution path (Docker or local), then inspect process runner/report parser only as required.

Do not inspect unrelated generation/model code unless the failure crosses that contract.

## Completion check

Verify:

- timeout is real, not cosmetic;
- output is bounded;
- processes/containers are cleaned up;
- Docker fallback remains correct;
- logs do not expose secrets;
- regression tests cover the failure mode.
