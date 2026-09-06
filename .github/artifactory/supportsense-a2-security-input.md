# A2 Security Requirements Input — from the A1 Phase 6 audit

**Status:** Forward-looking notes only. No solutions designed here — this is input for A2
Phase 1 (Requirements) so the design phase starts with these concerns already known rather
than discovering them mid-implementation, the way A1 discovered its method-security and
401/403 gaps only after something went looking.

A2 introduces live LLM-based ticket classification (Spring AI, per the locked technology
stack). Every concern below is a category of risk A1 structurally cannot have, because A1
never sends any data to a model or persists any model output.

---

## SEC-A2-1 — Prompt injection into the classifier

The ticket `body` field is free-text, customer-supplied, and — per FR-6/A2 — will be sent
to an LLM for classification and (eventually) auto-answer drafting. A customer can write
anything in a support ticket body, including text engineered to manipulate the model:
instructions to ignore its system prompt, to always classify as a specific category
regardless of content, to leak the system prompt itself, or to induce the model to draft an
auto-answer containing attacker-chosen content that later gets shown to a human agent or
(in a later milestone) sent back to the customer.

**Why this matters to a real client, not just a test:** ticket bodies are the single input
surface in this entire system that is guaranteed to be adversarial-quality text from
day one — unlike registration or ticket metadata, there's no realistic way to "just
validate" a support ticket's free-text content. This is the highest-likelihood attack
surface A2 introduces.

**Needs answering in A2 requirements, not now:** what is the trust boundary between
ticket-body content and the system prompt / classification instructions? Is model output
ever used to make an authorization or routing decision that a customer could manipulate
(e.g., forcing mis-routing to a specific team, or forcing an auto-answer to bypass the
sensitive-topic pre-screen that A1 already built)?

## SEC-A2-2 — Customer PII crossing to a third-party model provider

A1's AI provider strategy (sheet 03) wires both Ollama (`local`) and Gemini (`cloud`)
behind a common `ChatModel`/`EmbeddingModel` abstraction, but A1 never calls either — no
ticket content has left this system's boundary yet. A2 changes that: `customerEmail`,
`subject`, and `body` (which may contain account numbers, names, addresses, or other PII
depending on what a customer writes into a support ticket) will be sent to whichever
provider is configured, and for the `cloud` profile that means a third-party (Google)
processing customer data.

**Why this matters to a real client:** this is a data-processing-agreement / data-residency
/ compliance question as much as a security one — the `Legal / Compliance` and
`Security / Privacy` categories already seeded in A1's taxonomy exist precisely because
tickets can legitimately contain GDPR/CCPA-relevant content (see A1's pre-screen term list:
`GDPR`, `CCPA`, `DPA`, `data deletion`, `right to be forgotten`, `PII`, `breach`). Sending
that same content to a third-party model provider without a considered policy is a direct
contradiction of the pre-screen's own stated purpose.

**Needs answering in A2 requirements, not now:** is there a redaction/minimization step
before content leaves the system boundary for the `cloud` profile? Does the `local`
(Ollama) profile become mandatory for any ticket that trips the existing sensitive-topic
pre-screen, rather than routing to auto-answer at all? What's retained by the provider
(prompt logging, fine-tuning opt-out) under whatever commercial agreement is in place?

## SEC-A2-3 — Unbounded LLM cost as a denial-of-wallet vector

A1's ingestion path already has `POST /api/tickets/bulk` (max 500 items) and unauthenticated
`POST /api/tickets` (any authenticated user, any role, no rate limit — see Phase 6 finding
on missing login rate-limiting, which compounds this). Every ticket ingested in A2 will
trigger at least one LLM call. There is currently no cap, no budget, no circuit breaker, and
(per the Phase 6 audit) no rate-limiting anywhere in the ingestion path.

**Why this matters to a real client:** this isn't a hypothetical — cloud LLM providers bill
per token, and a single authenticated AGENT account (or a compromised one, or a leaked test
credential) submitting a bulk batch of 500 tickets with large bodies, repeated continuously,
is a direct and quantifiable cost-attack surface that didn't exist in A1 because A1 never
calls a model. This is denial-of-wallet, not denial-of-service, and is easy to miss because
it doesn't show up as an availability incident — it shows up as a bill.

**Needs answering in A2 requirements, not now:** per-account or per-team request/cost
budgets? A circuit breaker on provider spend? Does the existing bulk-endpoint role
restriction (`ADMIN`/`SERVICE` only) sufficiently narrow this, or does the plain
`POST /api/tickets` path (open to any authenticated `AGENT`, no rate limit) need its own cap
once it triggers a real model call instead of a no-op?

## SEC-A2-4 — Untrusted model output being persisted or rendered

A1's `TriageResult`/`DuplicateLink` tables are explicitly persistence-only in A1 — schema
and entity, no service, no endpoint, nothing writes to them (verified during Phase 5 recon).
A2 is where that changes: classification results, confidence scores, and (later) drafted
responses will be persisted and eventually surfaced to a human agent through the UI (A6) or
directly to the customer.

**Why this matters to a real client:** this is a second-order consequence of SEC-A2-1 — if
prompt injection can influence model output, and that output is later rendered without
treatment, the ticket body becomes a stored-XSS or content-injection vector mediated through
the model rather than directly. The Phase 6 audit already flagged `CreateTicketRequest`'s
`subject`/`body` fields as having no length cap and no HTML-encoding concern *today* only
because nothing renders them yet (informational, not a finding, in A1). A2 changes that
premise the moment model-drafted content derived from that same field is persisted and
later displayed.

**Needs answering in A2 requirements, not now:** is model output treated as untrusted user
content for rendering purposes (output-encode on display, same as any other user-controlled
field)? Is there a length/content cap on what gets persisted from a model response? Does the
existing sensitive-topic pre-screen (which currently blocks auto-answer, not classification)
need to also gate whether model output is ever auto-persisted without human review?

---

## What this file is NOT

This file does not propose solutions, does not estimate effort, and does not commit to any
design (e.g., "add redaction," "add a circuit breaker") — those are A2 Design-phase
decisions, to be made with full context once A2 requirements elicitation runs. Recording
the concerns here only ensures A2 Phase 1 (Requirements) starts from a list rather than
rediscovering each of these mid-build, the way A1 discovered its own security gaps only
after Phase 6 went looking.
