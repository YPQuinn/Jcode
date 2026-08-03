# Research: Provider-neutral thinking levels for `ai.ModelRequest`

**Status:** research brief (no production code changes)

**Worktree / branch:** `/private/tmp/jcode-research-provider-neutral-thinking-levels` · `research/provider-neutral-thinking-levels`

**Access date (primary web docs):** 2026-07-30
**Local reference markers:**

- pi agent types: `/Users/quinncypp/Workspace/personal/Jcode/docs/references/pi/packages/agent/src/types.ts` (`ThinkingLevel`, `AgentState.thinkingLevel`, `AgentLoopTurnUpdate.thinkingLevel`)
- pi agent loop: `/Users/quinncypp/Workspace/personal/Jcode/docs/references/pi/packages/agent/src/agent-loop.ts` (passes loop config / simple stream options through to the model stream fn)
- pi-ai types & clamp helpers: `packages/ai/src/types.ts`, `packages/ai/src/models.ts` (`getSupportedThinkingLevels`, `clampThinkingLevel`) on earendil-works/pi `main` (fetched 2026-07-30)
- pi-ai adapters: `packages/ai/src/api/{openai-responses,anthropic-messages,google-generative-ai,simple-options}.ts` on earendil-works/pi `main`
- pi-book: `docs/references/pi-book` baseline **pi-mono v0.66.0** analysis; thinking-level map / clamp documented in [ch18-model-registry.md](file:///Users/quinncypp/Workspace/personal/Jcode/docs/references/pi-book/src/ch18-model-registry.md); unified provider/api split in [ch04-provider-registry.md](file:///Users/quinncypp/Workspace/personal/Jcode/docs/references/pi-book/src/ch04-provider-registry.md)
- Jcode current seam: `ai/.../client/ModelRequest.java` (fields: `model`, `systemPrompt`, `messages`, `tools` only)

This brief separates **verifiable provider facts** from a **Jcode recommendation**. Provider contracts vary by model family and change frequently; do not treat any single enum as universally wire-identical.

---

## 1. Verifiable provider facts

### 1.1 OpenAI (Responses / reasoning models)

**Primary sources**

- [Reasoning models | OpenAI API](https://developers.openai.com/api/docs/guides/reasoning) (accessed 2026-07-30)
- [Model guidance | OpenAI API](https://developers.openai.com/api/docs/guides/latest-model) (accessed 2026-07-30)

**Contract (summary)**

| Concern | Fact |
| --- | --- |
| Control surface | `reasoning.effort` on the Responses API (and related chat reasoning knobs on older paths). |
| Representable efforts | **Model-dependent** set that **can include** `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`. |
| Default when omitted | **Model-dependent**, not universal. Docs state e.g. GPT-5.5 defaults to `medium`; GPT-5.6 defaults to `medium` when `reasoning.effort` is omitted (in both `standard` and `pro` modes). |
| Explicit off | Closest wire value is **`none`** where the model supports it (latency-critical, no/minimal reasoning). Not every reasoning model accepts `none`. |
| Unsupported level | Docs: “Some models support only a subset… check the relevant model page.” Unsupported values are a **provider validation / 4xx** concern, not a silent universal clamp at the HTTP API. |
| Orthogonal knobs | GPT-5.6 also has `reasoning.mode` (`standard` \| `pro`) **independent** of effort; plus `reasoning.summary`, `reasoning.context`. These are **not** effort levels. |

**Uncertainty / version variation**

- Exact supported effort sets differ across o-series, GPT-5.x snapshots, and Codex variants.
- Official TypeSpec / SDK snippets sometimes lag the guide’s full `xhigh`/`max` set; treat the live model page + reasoning guide as authoritative over third-party tables.

### 1.2 Anthropic (Messages API thinking + effort)

**Primary sources**

- [Extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking) (accessed 2026-07-30; page titled around supported models / manual budgets)
- [Adaptive thinking / steering](https://platform.claude.com/docs/en/build-with-claude/adaptive-thinking) (accessed 2026-07-30)
- [Effort](https://platform.claude.com/docs/en/build-with-claude/effort) (accessed 2026-07-30)

**Contract (summary)**

| Concern | Fact |
| --- | --- |
| Two thinking **modes** | **Manual:** `thinking: { type: "enabled", budget_tokens: N }` (fixed budget). **Adaptive:** `thinking: { type: "adaptive" }` with depth steered by **`output_config.effort`**. |
| Mode lifecycle | Manual `type: "enabled"` + `budget_tokens` is **deprecated on Claude 4.6** (still succeeds) and **rejected (400) on Claude 4.7+**. Older 4.5-and-earlier thinking models are extended-thinking-only (`type: "adaptive"` 400s there). |
| Effort levels | Documented: `low`, `medium`, `high`, `xhigh`, `max`. **`high` is the API default**; setting `effort: "high"` ≡ omitting effort. |
| No official `minimal` / `none` effort | Effort table does **not** list `minimal` or `off`/`none`. Lower effort reduces how often/how deeply the model thinks under adaptive mode; it is **soft guidance**, not a hard token budget. |
| Explicit thinking off | Separate from effort: thinking can be configured disabled on some models (`thinking.type: "disabled"` appears in effort docs constraints). **Claude Opus 5 rejects** `thinking: {"type":"disabled"}` at `xhigh` or `max` (400). |
| Budget rules (manual) | `budget_tokens` ≥ 1024 and generally `< max_tokens` (interleaved-thinking exception). Budgets are a **target**, not a hard cap; `max_tokens` remains the hard ceiling. |
| Cache sensitivity | Changing effort or `budget_tokens` invalidates prompt-cache breakpoints (value is rendered into the prompt). |

**Uncertainty / version variation**

- Which models support `xhigh` vs only `max` differs (docs call out that some models supporting `max` lack `xhigh`).
- Adaptive vs manual availability is **per model generation**; adapters must branch on model family, not a single global Anthropic path.
- Effort affects **all** output tokens (text, tools, thinking when active), not only “thinking blocks.”

### 1.3 Google Gemini (thinkingConfig)

**Primary sources**

- [Gemini thinking (Interactions / thinking guide)](https://ai.google.dev/gemini-api/docs/thinking) (accessed 2026-07-30; page notes last updated 2026-07-30 UTC)
- [Generating content with thinking](https://ai.google.dev/gemini-api/docs/generate-content/thinking) (accessed 2026-07-30)
- Official SDK shape: [`ThinkingConfig`](https://googleapis.github.io/js-genai/release_docs/interfaces/types.ThinkingConfig.html) — `includeThoughts`, `thinkingBudget`, `thinkingLevel` (`thinkingBudget`: **0 = DISABLED**, **-1 = AUTOMATIC**; ranges model-dependent)

**Contract (summary)**

| Concern | Fact |
| --- | --- |
| Gemini 3.x control | Preferred: **`thinkingLevel` / `thinking_level`**: `minimal`, `low`, `medium`, `high` (support matrix is **per model**). |
| Gemini 2.5 control | **`thinkingBudget`** token int; **0 disables** where allowed; **-1** dynamic; omit → model default (often dynamic). |
| Defaults | Dynamic thinking by default; concrete default level differs (e.g. gemini-3.6-flash **medium**, gemini-3.1-pro-preview **high**, gemini-2.5-flash-lite default **Off** in the Interactions table). |
| Explicit off | **Not universal.** Docs: cannot disable thinking for Gemini 3.1 Pro; Gemini 3 Flash / Flash-Lite do **not** support full thinking-off. `minimal` is described as matching “no thinking” for **most** queries but **does not guarantee** thinking is off. 2.5 Flash can use `thinkingBudget = 0`; 2.5 Pro **cannot** disable. |
| Unsupported level | Sending an unsupported level (e.g. `medium` on a model that only allows `low`/`high`) is a **provider 400** risk. |

**Uncertainty / version variation**

- Interactions API uses `generation_config.thinking_level` / summaries; generateContent uses `thinkingConfig.thinkingLevel` / `thinkingBudget`. Same concepts, different envelopes.
- Gemma 4 and other Google-served families may accept only a subset of levels (observed in ecosystem issues; confirm per model card before hard-coding).

---

## 2. Reference design: pi (intent, not a Jcode mandate)

### 2.1 Vocabulary

| Layer | Type | Values |
| --- | --- | --- |
| pi-ai simple stream | `ThinkingLevel` | `minimal` \| `low` \| `medium` \| `high` \| `xhigh` \| `max` |
| pi-ai model support | `ModelThinkingLevel` | `off` \| above |
| pi agent state / loop | `ThinkingLevel` | `off` \| `minimal` \| `low` \| `medium` \| `high` \| `xhigh` \| `max` |

Sources: pi `packages/ai/src/types.ts`; pi `packages/agent/src/types.ts` (local Jcode reference copy).

Notes from pi:

- `xhigh` and `max` are **opt-in** via non-null `model.thinkingLevelMap` entries; missing map entries for those levels ⇒ unsupported.
- Standard levels through `high` are assumed available unless mapped to `null`.
- `getSupportedThinkingLevels` / `clampThinkingLevel` snap an unsupported request to the **nearest higher, else nearest lower** supported level (not a hard error at the simple layer).
- pi-book ch18 documents `thinkingLevelMap` + clamp helpers as the replacement for older `compat.reasoningEffortMap` / `supportsXhigh()`.

### 2.2 Adapter translation patterns (pi `streamSimple`)

| Provider API | Omitted / no simple reasoning | Explicit off | On (level) |
| --- | --- | --- | --- |
| OpenAI Responses | If model.reasoning and map allows: may send `reasoning.effort` from `thinkingLevelMap.off` (default wire `"none"`) in some paths; simple path maps clamped `"off"` → **omit** `reasoningEffort` | `"off"` → no effort field after clamp | clamp → `thinkingLevelMap[level] ?? level` as `reasoning.effort`; often pairs summary |
| Anthropic Messages | `thinkingEnabled: false` | same as omit in simple path | Adaptive models: `thinkingEnabled` + `effort` (`minimal`/`low`→`low`, …; map override). Legacy: budget tokens from level via `adjustMaxTokensForThinking` |
| Google Generative AI | `thinking: { enabled: false }` → disabled config (`thinkingBudget: 0` or lowest Gemini 3 level without includeThoughts) | same | Gemini 3 / Gemma 4: `thinkingLevel`; 2.5: `thinkingBudget` tables; `xhigh`/`max` clamped to `high` before mapping |

**Important semantic gap in pi:** agent `ThinkingLevel` includes `"off"`, while simple options’ `reasoning?: ThinkingLevel` in pi-ai is the non-off union; adapters treat **missing `reasoning`** and **off** as the disable path. Jcode should make omit vs off **explicit** in the Java model (see §3).

### 2.3 What pi deliberately does *not* put on the unified simple request

- No generic `Map<String,Object>` request options bag on the simple path.
- Provider-specific knobs (`reasoningSummary`, `thinkingBudgetTokens`, `thinkingDisplay`, `includeThoughts`, OpenAI `reasoning.mode`) stay on **typed per-API options** (`stream` / `complete`), not on the unified level enum.
- Token budgets for Anthropic/Google are **adapter-owned defaults** (optionally overridden by `thinkingBudgets` on simple options), not a second user-facing enum.

---

## 3. Jcode recommendation (decision record)

### 3.1 Representable levels in `ai`

Standardize a single public enum in the `ai` module:

```text
ThinkingLevel:
  OFF | MINIMAL | LOW | MEDIUM | HIGH | XHIGH | MAX
```

Rationale:

- Intersects the practical vocabulary shared by OpenAI efforts, Gemini levels, Anthropic efforts (with `MINIMAL`→Anthropic `low`, `OFF`→OpenAI `none` / Gemini disable-or-minimal / Anthropic thinking disabled or lowest effort), and pi agent state.
- `XHIGH` / `MAX` must remain **first-class names** even though many models reject them — rejection/clamp is an adapter + model-metadata concern, not a reason to drop the names from the neutral enum.

Do **not** add provider-native aliases (`none`, `adaptive`, `pro` mode, numeric budgets) to this enum.

### 3.2 Omitted / default vs explicit off vs unsupported

Put thinking on `ModelRequest` as an **optional** field (Java: `@Nullable ThinkingLevel thinkingLevel` or `Optional` equivalent consistent with project style). Three distinct client intents:

| Client intent | `ModelRequest` encoding | Neutral meaning | Adapter duty |
| --- | --- | --- | --- |
| **Unspecified / provider default** | `thinkingLevel == null` (field omitted) | “Do not assert a level; use the **model’s native default**.” | Prefer **omitting** native thinking/effort fields when the provider default is correct. Do **not** invent a fake global default such as always `MEDIUM`. |
| **Explicit off** | `thinkingLevel == OFF` | “Disable or minimize thinking as hard as this model allows.” | Map to the strongest disable/minimize signal: OpenAI `none` (if supported); Anthropic thinking disabled or non-thinking request shape for the model family; Gemini `thinkingBudget=0` or lowest allowed level when full off is impossible. |
| **Explicit level** | `MINIMAL`…`MAX` | “Request this ordinal depth.” | Translate via per-model capability map to native effort/level/budget. |

**Unsupported-level behavior (recommended default policy):**

1. **Capability-aware clamp** (pi-compatible): if the resolved model metadata marks a level unsupported, clamp to nearest supported level (prefer higher, then lower). Encode the **effective** level only on the wire.
2. **Do not** fail the `ModelClient.stream` call synchronously.
3. Optional later strict mode (out of scope for first cut): stream-terminal `ERROR` if the caller demands exact level match — useful for eval harnesses, not for interactive agents.

**Models with no reasoning capability:**

- `null` and `OFF`: no-op (ignore).
- Non-off levels: **ignore** (pi simple path) *or* clamp to off — recommend **ignore + no wire field** so non-reasoning chat models stay cheap and quiet. Document that agent UIs should grey out levels using model metadata when available.

**Models that cannot fully disable thinking (e.g. Gemini 3.1 Pro):**

- `OFF` means **best-effort minimum** (e.g. `LOW` / `MINIMAL`), not a guarantee of zero thinking tokens. Adapters must not pretend otherwise.

### 3.3 Is a generic request-options abstraction warranted **now**?

**No.** Recommendation: **do not** add a free-form `RequestOptions` / `Map` / JSON bag to `ModelRequest` in this wave.

Reasons aligned with Jcode conventions and current provider reality:

- Project anti-pattern: avoid `Map<String,Object>` parameter bags; prefer explicit types.
- The only cross-provider concept with stable product demand today is **ordinal thinking depth** (+ explicit off).
- High-churn / provider-private knobs (`reasoning.mode=pro`, Anthropic `thinking.display`, Gemini `includeThoughts`, manual `budget_tokens`, OpenAI summary granularity) are **adapter configuration** or future **typed per-provider request extensions**, not neutral protocol.
- A bag would freeze an unstable union of three vendors’ orthogonal controls into the zero-dependency `ai` module too early.

**Revisit** a structured `ModelRequestOptions` record only when a second neutral concern appears with clear agent-core call sites (e.g. unified cache retention, or neutral max-output already needed by the loop). Even then, keep thinking as a named field, not a map entry.

### 3.4 Exact adapter validation / translation responsibilities

Future `ai-provider-*` adapters (and any in-tree test doubles) own:

1. **Resolve model capabilities** for the `ModelRef` (reasoning supported?; supported level set; whether full off exists; adaptive vs budget Anthropic path; Gemini level vs budget path). Until a catalog exists, adapters may hard-code family tables behind the SPI — still **not** the loop’s job.
2. **Interpret null vs OFF vs level** per §3.2.
3. **Clamp or reject** unsupported levels per policy (§3.2); default clamp.
4. **Translate** to native payload fields only:
   - OpenAI Responses: `reasoning.effort` ∈ model set; map `OFF`→`none` when supported else omit/lowest; never put Jcode enum strings on the wire blindly if the model map says otherwise.
   - Anthropic: choose adaptive (`type=adaptive` + `output_config.effort`) vs manual (`enabled` + `budget_tokens`) from model generation; map `MINIMAL`→`low`; map `OFF`→ thinking disabled / omit thinking for non-thinking models; never send `effort: "minimal"` or `effort: "off"`.
   - Gemini: Gemini 3 → `thinkingLevel`; Gemini 2.5 → `thinkingBudget`; `OFF`→ `0` or lowest level; keep `includeThoughts` as adapter policy (product default can match pi: include when thinking enabled).
5. **Keep failures in-stream** (`AssistantMessageEvent.Error` / `StopReason.ERROR|ABORTED`) — `ModelClient` must not throw synchronously (existing SPI contract).
6. **Do not** read env vars or API keys inside `agent-core`; thinking translation stays in the provider adapter with the rest of wire auth/config.
7. **Do not** write terminate/thinking-budget fields into standard `Message` transcript types; thinking **content** blocks remain a separate streaming/content concern already foreshadowed by pi’s thinking events (out of scope except that adapters may emit thinking content when the protocol supports it).

### 3.5 Compatibility with existing `ModelRequest` / `ai` types

Current:

```java
public record ModelRequest(
    ModelRef model,
    String systemPrompt,
    List<Message> messages,
    List<ToolSpec> tools
) { ... }
```

**Recommended minimal protocol extension (implementation later, not in this research commit):**

```text
ModelRequest(
  ModelRef model,
  String systemPrompt,
  List<Message> messages,
  List<ToolSpec> tools,
  @Nullable ThinkingLevel thinkingLevel  // null = unspecified/default
)
```

Companion type:

```text
site.pplee.jcode.ai.model.ThinkingLevel  // or ai.client / ai.thinking package; prefer model/ or client/ consistency
```

Compatibility notes:

- Additive, nullable field preserves binary/source compatibility for call sites once implemented with an overload or null-default factory if needed.
- `Model` / `ModelRef` today carry **identity only** (provider, api, modelId, name). Capability maps (`reasoning`, supported levels) belong on a **future resolved catalog model**, not on every `ModelRequest`, matching pi’s split between `Model` metadata and per-request `reasoning` option.
- `agent-core` should eventually thread an agent-level thinking preference (config/state/prepareNextTurn) into `ModelRequest.thinkingLevel` at the model-call boundary — same layering as pi’s `AgentState.thinkingLevel` → `SimpleStreamOptions.reasoning`. That is an agent-core concern; `ai` only defines the neutral field and enum.
- No change to `Message`, `ToolSpec`, or stream event seals is required to **accept** a level; emitting thinking deltas is a separate protocol decision.

### 3.6 Decision summary (what to standardize now)

| Topic | Decision |
| --- | --- |
| Neutral levels | `OFF`, `MINIMAL`, `LOW`, `MEDIUM`, `HIGH`, `XHIGH`, `MAX` |
| Default / omit | `null` on `ModelRequest` = provider/model native default |
| Explicit off | `OFF` = best-effort disable/minimize; not always zero thinking tokens |
| Unsupported | Adapter clamps via model metadata (default); no sync throw |
| Generic options bag | **Not now** |
| Budgets / summaries / pro mode | Adapter-owned or future typed provider options |
| SPI error style | Unchanged: encode in stream |

---

## 4. Mapping cheat-sheet (neutral → native)

> Illustrative defaults for adapter authors. Always prefer per-model metadata when present. **Not** a guarantee of provider acceptance.

| Neutral | OpenAI `reasoning.effort` | Anthropic | Gemini 3.x `thinkingLevel` | Gemini 2.5 `thinkingBudget` |
| --- | --- | --- | --- | --- |
| `null` | omit (model default) | omit thinking/effort (model default) | omit (model default) | omit (model default / dynamic) |
| `OFF` | `none` if supported; else lowest / omit | disable thinking when allowed; else lowest effort + no manual budget | lowest allowed (`minimal`/`low`); never claim full off on Pro | `0` if allowed; else min range / dynamic |
| `MINIMAL` | `minimal` if supported else `low` | effort `low` (+ adaptive or small budget on legacy) | `minimal` if supported else `low` | small positive budget (family table) |
| `LOW` | `low` | `low` | `low` | low-tier budget |
| `MEDIUM` | `medium` | `medium` | `medium` if supported else clamp | mid budget |
| `HIGH` | `high` | `high` (≡ Anthropic omit default when intentional high) | `high` | high-tier budget |
| `XHIGH` | `xhigh` if supported else clamp | `xhigh` if supported else `max`/`high` | clamp to `high` | clamp to high budget |
| `MAX` | `max` if supported else `xhigh`/`high` | `max` if supported else `xhigh`/`high` | clamp to `high` | clamp to high budget |

---

## 5. Open risks

1. **Provider churn:** OpenAI effort sets, Anthropic adaptive migration, and Gemini level matrices change by snapshot. Neutral enum stability ≠ wire stability; adapters and model catalogs must version.
2. **Omit vs off confusion:** If agent-core defaults state to `OFF` instead of `null`, users will not get provider defaults (e.g. Anthropic default `high`, Gemini dynamic). Product default should likely be **`null` or `MEDIUM`**, not `OFF` — final product choice deferred.
3. **False “off”:** Gemini 3.x and some Anthropic effort floors cannot honor true disable; billing/latency may still include hidden thinking tokens.
4. **Anthropic dual control plane:** effort ≠ thinking mode; mapping only levels without mode selection will break across 4.5 / 4.6 / 4.7+.
5. **OpenAI orthogonal controls:** `reasoning.mode=pro`, summaries, and context carry-over are out of scope; agents that need them will require provider-specific configuration channels later.
6. **Missing catalog in Jcode:** Without `thinkingLevelMap`-like metadata on resolved models, adapters will hard-code fragile family switches; plan catalog fields when `ai-provider-*` lands.
7. **pi simple-path quirk:** pi treats missing simple `reasoning` as off for Anthropic/Google, which **collapses omit and off**. Jcode should **not** copy that collapse if it wants true provider defaults.
8. **Cache invalidation:** Anthropic cache breaks when effort/budget changes mid-conversation; agent-core may want sticky thinking per session (product policy).

---

## 6. Sources

### Kept (primary / reference)

- OpenAI — [Reasoning models](https://developers.openai.com/api/docs/guides/reasoning) — effort values, defaults, mode orthogonality.
- OpenAI — [Model guidance](https://developers.openai.com/api/docs/guides/latest-model) — GPT-5.6 effort set and intentional configuration guidance.
- Anthropic — [Extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking) — manual budgets, deprecation/rejection timeline.
- Anthropic — [Adaptive thinking](https://platform.claude.com/docs/en/build-with-claude/adaptive-thinking) — adaptive decisioning, effort steering table.
- Anthropic — [Effort](https://platform.claude.com/docs/en/build-with-claude/effort) — effort levels, default `high`, interaction with thinking disable.
- Google — [Gemini thinking](https://ai.google.dev/gemini-api/docs/thinking) — per-model default/level table (Interactions).
- Google — [generateContent thinking](https://ai.google.dev/gemini-api/docs/generate-content/thinking) — `thinkingLevel` vs `thinkingBudget`, disable limits.
- Google GenAI SDK — [ThinkingConfig](https://googleapis.github.io/js-genai/release_docs/interfaces/types.ThinkingConfig.html) — `0` disabled / `-1` automatic.
- pi local — `packages/agent/src/types.ts`, `agent-loop.ts` — agent-facing level including `off`.
- pi upstream `main` — `packages/ai/src/{types,models}.ts`, `api/{openai-responses,anthropic-messages,google-generative-ai,simple-options}.ts` — unified level, clamp, adapter maps.
- pi-book — `ch04-provider-registry.md`, `ch18-model-registry.md` — provider/api split; `thinkingLevelMap` + clamp semantics.
- Jcode — `ai/.../ModelRequest.java`, `ModelClient.java`, `Model.java`, `ModelRef.java` — current seam constraints.

### Dropped / deprioritized

- Azure OpenAI reasoning pages — secondary mirror of OpenAI; not required for neutral design.
- Vercel / DigitalOcean / OpenRouter gateway docs — third-party remaps; not primary contracts.
- Community forum compatibility matrices — useful hints, not authoritative.
- Bedrock-only Claude pages — partner packaging of Anthropic; defer until Bedrock adapter scope.

---

## 7. Gaps / next steps

- Confirm exact OpenAI effort enums per concrete model IDs Jcode will ship first (model pages, not only the umbrella guide).
- Confirm Anthropic per-model table for adaptive vs manual and whether `thinking.type: disabled` is exposed on each target SKU.
- Decide product default when agent UI does not specify a level (`null` vs `MEDIUM`).
- When implementing: add unit tests for clamp tables and for “null does not send effort” vs “OFF sends disable/none”.
- Defer thinking **content** streaming schema alignment to a separate brief if not already covered by Wave 1 stream events.
