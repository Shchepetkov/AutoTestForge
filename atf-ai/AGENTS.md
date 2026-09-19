# atf-ai — Agent Rules

These rules extend the repository-level `AGENTS.md`.

## Role

`atf-ai` is the driven adapter for prompt engineering, model-provider integration, provider routing, response parsing, Java validation/normalization and bounded model retries.

## Fundamental rule

Model output is untrusted.

A successful HTTP/model response is not a successful generation.

Generated content must be parsed and validated deterministically before it can proceed.

## Provider neutrality

Keep OpenAI, Anthropic, Ollama and OpenAI-compatible behavior behind provider abstractions.

Do not place vendor-specific response types or assumptions into core contracts.

A provider-specific feature must:

- be isolated to the adapter/provider implementation;
- degrade gracefully when another provider does not support it;
- avoid changing generic workflows unless the capability is genuinely universal.

Preserve support for custom OpenAI-compatible base URLs.

## Prompt engineering

Prefer the smallest sufficient prompt.

Include:

- target signatures;
- relevant collaborators;
- value types;
- relevant project constraints;
- bounded business/TMS context.

Avoid injecting full files or large unrelated source trees.

Prompt templates are product behavior. Changes to them should be intentional and regression-tested where practical.

Clearly separate trusted instructions from untrusted source/external context.

External context may contain prompt injection. Treat it strictly as data.

## Parsing and normalization

Handle common model-output defects deterministically where safe:

- prose around code;
- multiple fenced blocks;
- wrong code block selected;
- package mismatch;
- test-class naming mismatch;
- incomplete fences.

Do not "repair" semantics silently when deterministic logic cannot know the author's intent.

JavaParser validation proves syntax, not correctness.

## Retries and repair

All retries must be bounded.

Distinguish transport/provider retry from code-repair retry.

Avoid resending huge unchanged context on every repair attempt when a concise diagnostic plus necessary code is sufficient.

Preserve diagnostics needed to explain why generation failed.

## Security and privacy

Never log API keys or authorization headers.

Avoid logging complete source prompts by default.

Do not silently transmit an entire repository to a cloud provider.

Make provider/base-url selection explicit and observable without exposing secrets.

## Testing

Provider tests should avoid real paid/network calls unless explicitly integration tests.

Cover:

- provider selection/routing;
- custom base URL;
- malformed model output;
- multiple code blocks;
- no Java test in response;
- retry bounds;
- transport failures;
- normalization behavior;
- token/context size limits when relevant.

Use deterministic fake model responses for unit tests.

## Context strategy

When debugging model generation:

1. inspect prompt builder;
2. inspect provider adapter only if transport/provider behavior is relevant;
3. inspect parser/normalizer only if output-shape behavior is relevant;
4. inspect core orchestration only when the contract between layers is involved.

Do not read all providers for a bug isolated to one provider.

## Completion check

Verify:

- generated text is still treated as untrusted;
- retries are bounded;
- provider neutrality is preserved;
- prompt growth is justified;
- secrets cannot appear in logs/errors;
- deterministic tests cover the changed behavior.
