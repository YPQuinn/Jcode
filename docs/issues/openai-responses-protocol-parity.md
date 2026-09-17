# OpenAI Responses protocol parity backlog

- **Status:** first-batch implemented (OAI-001–OAI-009); remaining P1/P2 still proposed
- **Scope:** `ai` and `ai-providers/openai`
- **Comparison baseline:** pi `origin/main@588915ec71714688cee8b7153339e8bdebb3e82e`. The local `docs/references/pi` checkout may be on an older commit that does not contain this baseline as an ancestor; verify evidence line numbers with `git show 588915ec:<path>` inside that clone rather than against the working tree.
- **Implemented in:** `docs/plans/archived/openai-responses-correctness-first-batch.md`
- **Validated Jcode baseline:** `mvn verify` — ai 46 / ai-providers 116 / agent-core 189 tests passed
- **Last reviewed:** 2026-09-16

## Purpose

This document records all issues found while comparing Jcode's OpenAI Responses implementation with pi, with emphasis on transcript conversion, provider-protocol adaptation, stream finalization, and transport behavior.

The goal is not line-for-line parity. Jcode must preserve its provider-neutral `ai` boundary, explicit assembly model, and stream error contract. Items marked as intentional divergences require no implementation unless the project contract changes.

## Priority definitions

| Priority | Meaning |
| --- | --- |
| P0 | Can cause invalid OpenAI requests, wrong terminal semantics, broken multi-turn continuity, or cancellation that cannot complete promptly. |
| P1 | Protocol-fidelity or feature gap affecting supported Responses behavior, compatible endpoints, or persisted message quality. |
| P2 | Resilience, observability, configurability, or defensive-normalization improvement without a default-path correctness failure. |
| Decision | Intentional project divergence or architecture decision that must be documented rather than copied from pi. |

## Backlog index

| ID | Priority | Summary | Primary area | Depends on |
| --- | --- | --- | --- | --- |
| OAI-001 | P0 | Preserve reasoning replay state with `store:false` | message model / request / response | **done** |
| OAI-002 | P0 | Add transcript normalization before Responses mapping | request conversion | **done** |
| OAI-003 | P0 | Map `response.incomplete` by provider reason | terminal mapping | **done** |
| OAI-004 | P0 | Propagate cancellation into active HTTP/SSE I/O | transport | **done** |
| OAI-005 | P1 | Finalize reasoning summaries from all event shapes | response mapping | **done** |
| OAI-006 | P1 | Preserve canonical message/refusal content and phase | response/message mapping | **done** |
| OAI-007 | P1 | Recover output slots when `output_item.added` is absent | response mapping | **done** |
| OAI-008 | P1 | Improve streamed tool-argument accumulation | response mapping | **done** |
| OAI-009 | P1 | Support SSE event type fallback from JSON `type` | SSE adaptation | **done** |
| OAI-010 | P1 | Normalize tool-result output, including empty and image results | request conversion / content model | multimodal design |
| OAI-011 | P1 | Select `developer` versus `system` by model capability | request conversion / capabilities | — |
| OAI-012 | P1 | Add Responses request controls and token clamp | request boundary | public API design |
| OAI-013 | P1 | Add prompt-cache, session-affinity, and custom-header controls | transport / config | public API and security design |
| OAI-014 | P1 | Support strict, grammar/custom, and deferred tools | tool model / request / response | public tool-schema design |
| OAI-015 | P1 | Preserve useful response and usage metadata | message/usage model | provider-neutral metadata design |
| OAI-016 | P2 | Add opt-in retry and structured provider-error handling | transport | OAI-004 recommended |
| OAI-017 | P2 | Sanitize invalid Unicode before request serialization | request conversion | — |
| OAI-018 | P1 | Add a protocol-parity regression matrix | tests | first-batch and second-batch delivered rules covered; deferred tools remain backlog |

## P0 — correctness and lifecycle

### OAI-001 — Preserve reasoning replay state with `store:false`

**Problem**

Jcode requests `store:false`, but its standard content model stores only visible thinking text. The adapter does not request `reasoning.encrypted_content`, does not preserve the returned reasoning item, and skips every thinking block during replay.

A previous function-call item id can therefore be replayed without the reasoning item to which OpenAI paired it. This can break same-model multi-turn reasoning and produce provider validation errors.

**Evidence**

- Jcode only maps reasoning effort: `ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiRequestMapper.java:145-175`.
- Jcode drops thinking on replay: `OpenAiRequestMapper.java:71-95`.
- `Content.Thinking` has no opaque replay metadata: `ai/src/main/java/site/pplee/jcode/ai/message/Content.java:24-29`.
- pi requests summary and encrypted content: `packages/ai/src/api/openai-responses.ts:312-321`.
- pi preserves and backfills the reasoning item: `packages/ai/src/api/openai-responses-shared.ts:515-530,667-672`.
- pi replays preserved reasoning for the same model: `openai-responses-shared.ts:219-224`.

**Required work**

1. Design a provider-neutral opaque replay-metadata seam; do not add OpenAI-named fields to `ai.Message` or `ai.Model`.
2. Record enough source model identity to decide whether opaque state is valid for the target model.
3. Request `reasoning.encrypted_content` whenever reasoning continuity needs it.
4. Capture the complete reasoning item from `output_item.done` and terminal response output.
5. Replay it only when compatible with the target provider/API/model.
6. Define safe degradation when metadata is unavailable, including whether function-call item ids must be omitted.

**Acceptance criteria**

- A `store:false` reasoning turn followed by a tool result can continue on the same model without a 400.
- Encrypted reasoning supplied only in `response.completed.response.output` is retained.
- Cross-model replay never sends a reasoning item that belongs to another model.
- No OpenAI-specific field leaks into the provider-neutral API by name.

---

### OAI-002 — Add transcript normalization before Responses mapping

**Problem**

`OpenAiRequestMapper` directly serializes every standard message. It does not remove incomplete assistant turns, repair orphaned tool-call sequences, normalize foreign ids, or transform content for model handoff.

**Evidence**

- Direct Jcode replay: `OpenAiRequestMapper.java:43-44,60-105`.
- Jcode assistant messages do not carry source provider/API/model identity: `ai/src/main/java/site/pplee/jcode/ai/message/Message.java:36-42`.
- pi transforms model-specific thinking and tool ids: `packages/ai/src/api/transform-messages.ts:95-140`.
- pi skips failed/aborted assistant messages: `transform-messages.ts:189-197`.
- pi synthesizes missing tool results: `transform-messages.ts:158-180,199-220`.
- pi omits paired `fc_*` item ids during same-provider model handoff: `packages/ai/src/api/openai-responses-shared.ts:213-216,252-262`.

**Required work**

1. Add a provider-neutral transcript normalization seam before dialect mapping.
2. Skip `ERROR` and `ABORTED` assistant turns or define an equally safe replacement rule.
3. Detect assistant tool calls that have no matching tool result and synthesize a deterministic error result where required.
4. Normalize raw/foreign tool-call ids and apply the same normalized call id to matching tool results.
5. Distinguish same-model, same-provider/different-model, and cross-provider replay.
6. Drop non-replayable thinking on model handoff. pi converts it to visible text; Jcode intentionally does not, because that promotes chain-of-thought visibility without an explicit redacted/visibility contract. Revisit only after such a contract exists.
7. Drop tool results whose call was never accepted (orphan results, or results belonging to a skipped `ERROR`/`ABORTED` assistant). pi passes them through; Jcode drops them because an unmatched `function_call_output` is rejected by the Responses API and promoting it to a user message would raise its instruction priority.

**Acceptance criteria**

- Failed and aborted partial assistant turns are not sent as completed Responses items.
- Every replayed function call has a valid matching output before a later user/assistant turn.
- Model switching does not retain item ids that trigger OpenAI reasoning-pair validation.
- Tool-call and tool-result ids remain consistent after normalization.
- Orphan tool results and handoff thinking never reach the request payload.

---

### OAI-003 — Map `response.incomplete` by provider reason

**Problem**

Jcode maps every `response.incomplete` event to successful `StopReason.LENGTH`. OpenAI uses incomplete reasons for more than output-token exhaustion, including content filtering and provider-specific limits.

**Evidence**

- Current mapping: `ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiEventMapper.java:172-178`.
- pi retains and interprets `incomplete_details.reason`: `packages/ai/src/api/openai-responses-shared.ts:565-575,742-759`.
- Jcode only tests `max_output_tokens`: `ai-providers/src/test/java/site/pplee/jcode/aiproviders/openai/OpenAiEventMapperTest.java:145`.
- pi tests content-filter and unknown reasons: `packages/ai/test/openai-responses-terminal-event.test.ts:322-344`.

**Required work**

- Map `max_output_tokens` to `LENGTH`.
- Map `content_filter`, missing reasons, and unknown non-length reasons to terminal `ERROR` with a useful provider message.
- Preserve the raw status/reason if OAI-015 introduces a standard metadata location.

**Acceptance criteria**

- Content-filtered incomplete responses emit `AssistantMessageEvent.Error`.
- Unknown incomplete reasons are not reported as successful length stops.
- Max-output truncation remains a successful `Done(LENGTH)` result.

---

### OAI-004 — Propagate cancellation into active HTTP/SSE I/O

**Problem**

Cancellation is polled before request dispatch and at SSE event boundaries. It cannot interrupt `HttpClient.send()` or a silent blocking body read. Provider shutdown also does not actively close an in-flight response body.

**Evidence**

- Blocking send: `ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiResponsesAdapter.java:68-95`.
- Event-boundary polling: `OpenAiResponsesAdapter.java:123-159`.
- pi passes an abort signal to the underlying request and retry wait: `packages/ai/src/api/openai-responses.ts:144-155`.

**Required work**

1. Use an interruptible asynchronous HTTP operation or explicitly track and close the active response body.
2. Bind `CancellationSignal` to the active exchange without casting it to a cancellation owner.
3. Ensure a silent SSE connection can be cancelled promptly.
4. Define `close()` behavior for active producers and make terminal emission exactly once.
5. Preserve partial content in the `ABORTED` result.

**Acceptance criteria**

- Cancellation interrupts a server that sends headers and then remains silent.
- Cancellation during connect/send and body read produces one `Error(ABORTED)` event.
- Closing the provider does not leave an unbounded active producer thread.

---

## P1 — protocol fidelity, capability coverage, and regression gates

### OAI-018 — Add a protocol-parity regression matrix

**Problem**

The existing 70 tests validate the current happy path but encode several incomplete assumptions, such as all incomplete responses being length stops and reasoning completion using only `item.content`.

**Required work**

Add deterministic fake-server/unit coverage for:

- same-model `store:false` encrypted reasoning replay;
- encrypted reasoning present only in terminal response output;
- reasoning + function-call replay and model handoff;
- failed/aborted assistant removal and orphan tool-result repair;
- `content_filter`, missing, and unknown incomplete reasons;
- silent-stream cancellation;
- reasoning summary multipart events;
- refusal-only `output_item.done`;
- `output_item.done` without a preceding added event;
- initial tool arguments plus deltas and partial JSON;
- JSON `type` fallback for data-only SSE;
- empty and image tool-result output;
- foreign/unsafe/oversized tool-call ids;
- maximum-output-token clamp and compatibility options;
- custom/grammar tool request and stream events if OAI-014 is implemented.

**Acceptance criteria**

- Every actionable item in this document has at least one regression test.
- Tests distinguish provider contract requirements from optional compatible-endpoint behavior.
- Live API tests remain optional; correctness does not depend only on credentials being available.

### OAI-005 — Finalize reasoning summaries from all event shapes

**Problem**

Jcode handles reasoning text deltas but ignores `response.reasoning_summary_part.done`. At `output_item.done` it reads only `item.content`, while OpenAI reasoning summaries can be canonicalized in `item.summary`.

**Evidence**

- Jcode delta/finalization behavior: `OpenAiEventMapper.java:84-92,151-159`.
- pi summary part and finalization behavior: `packages/ai/src/api/openai-responses-shared.ts:584-613,667-670`.

**Required work**

- Preserve summary-part boundaries deterministically.
- Prefer canonical `item.summary`, then `item.content`, then accumulated deltas.
- Integrate opaque reasoning metadata through OAI-001.

**Acceptance criteria**

- Multiple reasoning summary parts retain their intended separation.
- A reasoning item delivered only at `output_item.done` produces a non-empty final thinking block.

---

### OAI-006 — Preserve canonical message/refusal content and phase

**Problem**

Jcode reads `text` for both output text and refusal blocks, but Responses refusal blocks use a `refusal` field. It also inserts newlines between multiple final content blocks, does not preserve message item id/phase, and cannot distinguish commentary from final-answer output on replay.

**Evidence**

- Jcode final content extraction: `OpenAiEventMapper.java:235-250`.
- Jcode generates replacement ids on every request: `OpenAiRequestMapper.java:74-80,109-112`.
- pi reads `text` or `refusal` without inserting separators and preserves id/phase: `packages/ai/src/api/openai-responses-shared.ts:680-682,225-245`.

**Required work**

- Read the correct field for each content type.
- Preserve exact final text concatenation semantics.
- Decide how provider-neutral replay metadata stores output item identity and phase.
- Retain accumulated deltas only as a fallback when canonical final content is absent.

**Acceptance criteria**

- Refusal-only final events retain their refusal text even without deltas.
- Multiple content blocks are not changed by adapter-inserted newlines.
- Compatible message phase metadata can survive same-model replay.

---

### OAI-007 — Recover output slots when `output_item.added` is absent

**Problem**

Jcode discards `response.output_item.done` when no slot was created by `response.output_item.added`. pi creates a slot from the done item as a defensive compatibility behavior.

**Evidence**

- Jcode drops missing slots: `OpenAiEventMapper.java:130-139`.
- pi get-or-create behavior: `packages/ai/src/api/openai-responses-shared.ts:512-513,662-665`.

**Required work**

- Create the correct text/thinking/tool slot from a done item when possible.
- Emit a coherent start/end sequence before terminal completion.
- Reject conflicting item types for an already existing output index.

**Acceptance criteria**

- A complete done-only message, reasoning item, or function call is retained.
- Duplicate or conflicting lifecycle events cannot create duplicate content blocks.

---

### OAI-008 — Improve streamed tool-argument accumulation

**Problem**

Jcode does not seed the streaming buffer with initial `item.arguments`, and updates the partial `JsonNode` only after the complete buffer becomes valid JSON. pi preserves initial arguments and performs tolerant partial-JSON parsing on each delta.

**Evidence**

- Jcode initial and delta behavior: `OpenAiEventMapper.java:59-69,94-128`.
- pi initial and delta behavior: `packages/ai/src/api/openai-responses-shared.ts:469-476,634-650`.

**Required work**

- Seed the buffer from `output_item.added.item.arguments`.
- Add a bounded, defensive partial JSON parser or explicitly redefine partial-message semantics.
- Repair malformed control characters/escapes only when behavior is deterministic.
- Keep `ToolCallEnd` final arguments authoritative.

**Acceptance criteria**

- Initial arguments are not lost when later deltas are appended.
- Partial events expose the best valid accumulated argument object.
- Final arguments are exact and scratch buffers never enter the transcript.

---

### OAI-009 — Support SSE event type fallback from JSON `type`

**Problem**

The adapter dispatches semantic events only from the SSE `event:` field. A compatible endpoint that emits `data: {"type":"response..."}` without an `event:` field is parsed as generic `message` and ignored. pi's SDK path exposes the JSON event object's `type`.

**Evidence**

- Jcode defaults an unnamed SSE event to `message`: `ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiSseParser.java:25-67`.
- Jcode passes that name directly to the mapper: `OpenAiResponsesAdapter.java:138-150`.
- pi dispatches on `event.type`: `packages/ai/src/api/openai-responses-shared.ts:579-735`.

**Required work**

- When the SSE name is absent/generic, use textual JSON `type` as the semantic event name.
- When both are present and disagree, define whether to reject or prefer one source.
- Keep framing separate from semantic mapping.

**Acceptance criteria**

- Named official events continue to work.
- Data-only compatible events map correctly.
- Conflicting event identities fail deterministically rather than silently corrupting state.

---

### OAI-010 — Normalize tool-result output, including empty and image results

**Problem**

Jcode joins only text blocks. Empty results become an empty string, and the standard content model cannot express user image input or tool-result images.

**Evidence**

- Jcode output mapping: `OpenAiRequestMapper.java:100-124`.
- Jcode content union has no image variant: `ai/src/main/java/site/pplee/jcode/ai/message/Content.java:14-35`.
- pi emits `"(no tool output)"`, image placeholders, or Responses image content: `packages/ai/src/api/openai-responses-shared.ts:62-103`.

**Required work**

- Normalize an empty tool result to an explicit provider-safe placeholder.
- Design provider-neutral image content before adding Responses image conversion.
- Map user images to Responses `input_image` items for vision-capable models.
- For non-vision models, define deterministic user-image and tool-image placeholders.
- Keep tool-result images inside `function_call_output`; do not move them into a later synthetic user message.

**Acceptance criteria**

- Empty tool results never disappear semantically.
- Vision-capable Responses models receive user images as request input and tool-result images in the function-call output.
- Non-vision models receive explicit and stable placeholders for both user and tool-result images.

---

### OAI-011 — Select `developer` versus `system` by model capability

**Problem**

Jcode always emits the system prompt with role `system`. pi uses `developer` for reasoning models when the endpoint supports it.

**Evidence**

- Jcode fixed role: `OpenAiRequestMapper.java:37-40`.
- pi capability-based role: `packages/ai/src/api/openai-responses-shared.ts:172-179`.

**Required work**

- Add a provider-local `supportsDeveloperRole` capability.
- Use `developer` only for models/endpoints that support it.
- Preserve `system` for non-reasoning models and compatible endpoints that reject `developer`.

**Acceptance criteria**

- Reasoning-capable official OpenAI models use the configured preferred role.
- Compatibility configuration can force `system` without changing provider-neutral messages.

---

### OAI-012 — Add Responses request controls and token clamp

**Problem**

`ModelRequest` cannot express maximum output tokens, temperature, service tier, tool choice, or controlled provider payload extensions. pi supports these controls and clamps OpenAI `max_output_tokens` to at least 16.

**Evidence**

- Current Jcode request boundary: `ai/src/main/java/site/pplee/jcode/ai/client/ModelRequest.java:22-27`.
- pi parameter mapping: `packages/ai/src/api/openai-responses.ts:289-310`.

**Required work**

- Decide which controls are provider-neutral and belong in `ModelRequest`.
- Keep provider-only controls in explicit OpenAI configuration/options rather than untyped maps.
- Clamp non-zero OpenAI maximum output tokens to 16.
- Validate unsupported combinations before dispatch.

**Acceptance criteria**

- Callers can set a provider-neutral output-token limit and tool choice.
- OpenAI never receives `max_output_tokens` from 1 through 15.
- Unsupported options produce a terminal mapping error, not a synchronous exception.

---

### OAI-013 — Add prompt-cache, session-affinity, and custom-header controls

**Problem**

Jcode sends fixed authentication/content headers plus optional organization/project. It cannot set prompt cache keys/retention, session-affinity headers, proxy-specific authorization, or explicit safe custom headers.

**Evidence**

- Jcode request headers: `OpenAiResponsesAdapter.java:183-197`.
- Jcode configuration surface: `ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiProviderConfig.java:20-31`.
- pi cache/session/header behavior: `packages/ai/src/api/openai-responses.ts:213-285`.

**Required work**

- Add typed cache-retention and session-id configuration.
- Clamp prompt cache keys to OpenAI's 64-character limit.
- Map session affinity by endpoint compatibility: OpenAI, OpenAI-no-session, or OpenRouter.
- Design a safe custom-header policy with redacted diagnostics and explicit override rules.
- Prevent secrets from entering `toString()`, events, or errors.

**Acceptance criteria**

- Cache retention `none` omits cache keys and affinity headers.
- Official OpenAI and OpenRouter-compatible endpoints receive the correct header shapes.
- Explicit headers override defaults only according to documented rules.
- No credential is exposed by configuration diagnostics.

---

### OAI-014 — Support strict, grammar/custom, and deferred tools

**Problem**

Jcode declares only ordinary function tools and consumes only `function_call` events. It cannot represent strict JSON-schema intent, OpenAI grammar/custom tools, custom-tool input deltas, or deferred tool loading/search items.

**Evidence**

- Current Jcode tool declaration: `ai/src/main/java/site/pplee/jcode/ai/tool/ToolSpec.java:14-18`.
- Current request mapping: `OpenAiRequestMapper.java:127-142`.
- Current event types: `OpenAiEventMapper.java:46-71`.
- pi tool conversion: `packages/ai/src/api/openai-responses-shared.ts:344-379`.
- pi custom-tool stream handling: `openai-responses-shared.ts:487-508,651-661,706-718`.

**Required work**

1. Add a provider-neutral constrained-sampling declaration instead of OpenAI-specific tool fields.
2. Add provider-local capability switches for strict and grammar tools.
3. Map custom-tool input back to the standard `JsonNode` argument shape.
4. Decide whether deferred tool loading belongs in the initial product scope.
5. If supported, preserve tool-search call/output ordering during replay.

**Acceptance criteria**

- Plain function tools retain current behavior.
- Strict mode is emitted only when supported.
- Grammar/custom-tool streams produce the standard tool-call event sequence.
- Unsupported advanced tools fail or safely fall back according to an explicit policy.

---

### OAI-015 — Preserve useful response and usage metadata

**Problem**

Jcode discards the Responses response id, raw stop reason, output message id/phase, and reasoning-token breakdown. Its `Usage` type records aggregate tokens only.

**Evidence**

- Current usage mapping: `OpenAiEventMapper.java:269-283`.
- Current assistant message fields: `ai/src/main/java/site/pplee/jcode/ai/message/Message.java:36-42`.
- pi captures response id, raw reason, and reasoning tokens: `packages/ai/src/api/openai-responses-shared.ts:533-575,580-581`.

**Required work**

- Classify metadata as standard identity, usage detail, opaque replay metadata, or provider diagnostics.
- Add only provider-neutral concepts to `ai` public types.
- Preserve raw provider data only through a bounded/redacted representation.
- Decide whether cost calculation belongs outside the protocol layer; do not copy pi's pricing behavior automatically.

**Acceptance criteria**

- Response correlation id and raw terminal reason are available for diagnostics without exposing secrets.
- Reasoning tokens are represented as a subset of output tokens when reported.
- Provider-specific payload objects do not become an unbounded `Map<String,Object>` escape hatch.

## P2 — resilience and defensive normalization

### OAI-016 — Add opt-in retry and structured provider-error handling

**Problem**

Jcode performs one request and returns raw/truncated non-2xx bodies. pi exposes retry controls and normalizes provider error bodies. pi's current default retry count is also zero, so this is configurability and resilience work rather than a default-behavior parity bug.

**Evidence**

- Jcode single attempt and raw status handling: `OpenAiResponsesAdapter.java:85-100`.
- pi request wrapper: `packages/ai/src/api/openai-responses.ts:144-155`.
- pi retry default and retryable status rules: `packages/ai/src/utils/provider-retry.ts:1-105`.

**Required work**

- Keep retries disabled by default.
- Add explicit bounded retry count and maximum server-requested delay.
- Retry only transport errors and documented retryable statuses/headers.
- Make retry waits cancellation-aware through OAI-004.
- Parse standard OpenAI error envelopes into stable redacted messages.

**Acceptance criteria**

- Default behavior remains one attempt.
- Cancellation interrupts backoff immediately.
- `x-should-retry`, `retry-after-ms`, and `retry-after` obey documented bounds if supported.

---

### OAI-017 — Sanitize invalid Unicode before request serialization

**Problem**

pi sanitizes unpaired UTF-16 surrogates in prompts, message text, tool-result text, and custom-tool input before sending them to compatible APIs. Jcode delegates strings directly to Jackson and does not define malformed-Unicode behavior.

**Evidence**

- Jcode direct string mapping: `OpenAiRequestMapper.java:37-40,62-104`.
- pi sanitization calls for prompts, message text, and custom-tool input: `packages/ai/src/api/openai-responses-shared.ts:172-280`.
- pi tool-result text sanitization: `openai-responses-shared.ts:62-103`.

**Required work**

- Define a provider-neutral string sanitation utility with deterministic replacement behavior.
- Apply it at the provider serialization boundary, not by mutating the transcript.
- Cover prompt, message text, tool result text, names/descriptions where allowed, and custom-tool input.

**Acceptance criteria**

- An unpaired surrogate cannot make request serialization or provider parsing fail.
- Valid Unicode is byte-for-byte unchanged after JSON decoding.
- The canonical transcript remains unmodified.

## Intentional divergences and decisions

These differences are recorded so future parity work does not accidentally reverse established Jcode contracts.

### D-001 — `PROVIDER_DEFAULT` must omit explicit reasoning parameters

Jcode defines `ThinkingLevel.PROVIDER_DEFAULT` as “the adapter does not actively specify reasoning.” pi may send `reasoning.effort: "none"` by default for models whose catalog supports it.

- Jcode: `OpenAiRequestMapper.java:145-149`.
- pi: `packages/ai/src/api/openai-responses.ts:312-325`.

**Decision:** retain Jcode behavior unless the public `ThinkingLevel` contract is deliberately revised.

### D-002 — Unsupported explicit `OFF` fails rather than silently degrading

Jcode rejects explicit `OFF` when capabilities are unknown or the reasoning model has no OFF mapping. pi may omit reasoning in comparable cases.

- Jcode: `OpenAiRequestMapper.java:150-162`.

**Decision:** retain the fail-safe behavior because an explicit absolute level must not silently become provider default.

### D-003 — `Start` is emitted before asynchronous provider work

Jcode synchronously pushes `Start` before request mapping and dispatch. pi currently emits start after receiving the HTTP response.

- Jcode: `OpenAiResponsesAdapter.java:56-65`.
- pi: `packages/ai/src/api/openai-responses.ts:149-160`.

**Decision:** retain Jcode's stream contract. Mapping, configuration, HTTP, and cancellation failures must remain `Start -> Error`.

### D-004 — JDK HTTP versus provider SDK is not itself an issue

Jcode intentionally uses JDK `HttpClient`; pi uses the OpenAI SDK. Parity work concerns observable protocol semantics—cancellation, retry, headers, SSE decoding, and error normalization—not library choice.

### D-005 — Cost calculation remains outside the current protocol contract

pi calculates monetary cost from its model catalog and service tier. Jcode `Usage` currently records tokens only.

**Decision required:** add cost only if a real provider-neutral consumer needs it; do not expand this issue merely for implementation parity.

## Recommended delivery order

1. **OAI-003** — isolated terminal-semantic fix.
2. **OAI-004** — establish cancellation/lifecycle safety before adding transport options.
3. **OAI-001 + OAI-002** — jointly design replay metadata and transcript normalization.
4. **OAI-005 through OAI-009** — harden stream/event fidelity.
5. **OAI-010 through OAI-015** — expand request/content/tool/metadata capabilities through explicit API design.
6. **OAI-016 and OAI-017** — resilience hardening.
7. **OAI-018** evolves alongside every item and gates completion.

## Residual questions before implementation

1. What provider-neutral type should carry opaque same-model replay state without violating the prohibition on provider-specific fields in `ai.Message`?
2. Should source `ModelRef` become standard assistant-message metadata, or should provenance travel in a separate replay envelope?
3. Should transcript normalization be a shared `ai` utility, a provider adapter concern, or an explicit caller-provided projection stage?
4. Which request controls have real consumers now, and which should remain out of scope until a product module exists?
5. Is multimodal content required before a second provider is implemented, or should OAI-010 initially cover only explicit empty-result normalization?
6. Are OpenAI-compatible proxies part of the supported contract, or only official `api.openai.com` behavior?
