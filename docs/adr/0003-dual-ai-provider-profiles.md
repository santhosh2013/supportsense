# ADR-0003: Dual AI provider profiles

**Status:** Accepted

## Context

Sheet 03 mandates Ollama for zero-cost local development and Gemini for deployed inference.
A1 exercises no model at all, but the seam must exist from the start so A2 does not require
an architectural change to introduce it.

## Decision

Both providers are wired behind Spring AI's `ChatModel` / `EmbeddingModel` abstractions from
milestone A1, even though nothing calls them yet. `local` uses Ollama (`llama3.1`,
`nomic-embed-text`); `cloud` uses Gemini (`gemini-2.0-flash`, `text-embedding-004`). Both
embedding models are 768-dimensional, so the pgvector schema introduced in A3 is
provider-independent.

## Consequences

Business code never references a provider directly — only the active Spring profile and
its properties change. Switching providers requires no data migration. The trade-off: Llama
3.1 8B is measurably weaker than Gemini at structured classification with calibrated
confidence, so local iteration is cheap but the numbers that go on a resume must be measured
on the `cloud` profile, not the `local` one.
