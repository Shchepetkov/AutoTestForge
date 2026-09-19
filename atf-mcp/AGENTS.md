# atf-mcp — Agent Rules

These rules extend the repository-level `AGENTS.md`.

## Role

`atf-mcp` integrates external business/TMS context through Model Context Protocol and exposes normalized context to the core port.

## Protocol correctness

Implement MCP according to the protocol lifecycle, not assumptions from LSP or generic JSON-RPC transports.

Handle:

- initialization;
- capability/tool discovery;
- request IDs;
- notifications/server-initiated traffic where supported;
- error objects;
- malformed messages;
- server termination;
- noisy stderr/stdout scenarios allowed by the implementation;
- timeouts;
- cleanup.

Keep framing/transport behavior explicit and thoroughly tested.

## Process lifecycle

External MCP processes are expensive and untrusted.

Reuse a process per configured source when lifecycle semantics allow it.

Never leak orphan server processes on success, timeout, cancellation or failure.

Concurrent requests must not corrupt message/request matching.

A timed-out request must not poison future requests when recovery is possible.

## External content

Treat every MCP response as untrusted data.

Never allow retrieved content to become higher-priority instructions for the LLM.

Bound response size before storing or passing it into prompts.

Normalize structured content and legacy/text content consistently.

Do not trust tool names/arguments supplied by arbitrary external text.

## Fault isolation

External context is enrichment, not a reason to destroy the whole generation run unless the contract explicitly requires it.

Distinguish:

- source unavailable;
- tool unavailable;
- protocol error;
- timeout;
- malformed response;
- valid empty result.

Return enough diagnostic context for observability without exposing credentials.

## Security

Commands/args come from configuration; never concatenate user-controlled values into a shell command.

Prefer direct process argument arrays.

Avoid inheriting or logging sensitive environment values unnecessarily.

Do not permit path/command injection through query templates.

## Testing

Use fake MCP servers/processes.

Cover:

- normal stdio request/response;
- supported framing variants;
- initialization;
- tools/list;
- structured content;
- server-initiated requests supported by the client;
- noise;
- malformed JSON;
- timeout followed by another successful request;
- process crash;
- concurrent requests if supported;
- cleanup.

Tests must be deterministic and network-free.

## Context strategy

Start with the transport/client symbol implicated by the failure.

Inspect only the message parser/lifecycle code necessary to explain the behavior.

Use protocol documentation through Context7/web only for a concrete spec question; do not load broad docs without need.

## Completion check

Verify:

- protocol/lifecycle behavior is spec-aligned;
- request matching survives failure scenarios;
- outputs are bounded;
- processes are cleaned up;
- external content cannot override trusted instructions;
- fake-server regression tests cover the change.
