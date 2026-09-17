# AutoTestForge agent guide

AutoTestForge is a Java 17 multi-module Maven application that acts as an agent for generating, writing, running and repairing Java tests. Keep changes provider-neutral and preserve the hexagonal boundaries.

## Fast project map

- `atf-core`: domain, ports and the fixed agent loop `scan -> generate -> write -> validate -> repair`. It must remain framework-free.
- `atf-scanner`: JavaParser-based multi-module source analysis and dependency graph.
- `atf-ai`: prompts, response validation and model adapters. Providers are selected by `RoutingTestGenerator`.
- `atf-mcp`: outbound stdio MCP client for business/TMS context.
- `atf-writer`: safe test-file and Maven/Gradle dependency updates.
- `atf-validator`: Docker/local test execution and JUnit report parsing.
- `atf-cli`: command-line wiring.
- `atf-web`: REST/UI wiring, asynchronous jobs and the inbound Streamable HTTP MCP endpoint at `/mcp`.
- `examples/demo-project`: end-to-end fixture; do not treat it as production code.

## Important invariants

- Infrastructure depends on `atf-core`; core must not depend on Spring, LangChain4j, JavaParser, Docker or web classes.
- Model output is untrusted until parsed as Java. Never write raw model text directly.
- A failure for one target class must be reported without aborting the remaining classes.
- Validation and repair loops must stay bounded by `maxFixAttempts`.
- Web and MCP filesystem access must pass through `ProjectAccessPolicy`.
- MCP tools should return compact metadata first. Include source only when explicitly requested and always bound its size.
- MCP-triggered generation defaults to `dryRun=true`; writing requires an explicit false value.
- Never commit API keys, `.env`, logs or generated `target` directories.

## Model providers

- `offline`: deterministic smoke tests; use in CI and local verification.
- `ollama`: local Ollama model; Qwen can be selected by model name.
- `openai`: official OpenAI endpoint, registered only when a key exists.
- `compatible`: any OpenAI-compatible endpoint such as vLLM, LM Studio or LocalAI.

When adding a provider, update both CLI and Web `AtfProperties`/`AtfConfiguration`, both `application.yml` files, the Web UI option and README configuration table.

## Verification

Run the full build:

```bash
mvn verify
```

Run the no-network end-to-end smoke test:

```bash
java -jar atf-cli/target/atf-cli-0.1.0.jar generate \
  --project-path examples/demo-project --llm offline --dry-run
```

For web or MCP changes, test JSON-RPC `initialize`, `tools/list` and at least one `tools/call` against the packaged `atf-web` JAR. Docker deployment files are `Dockerfile`, `compose.yml` and `.env.example`.
