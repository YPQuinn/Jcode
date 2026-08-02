# Domain Docs

Engineering skills use a single-context domain documentation layout for this repository.

## Before exploring

- Read `CONTEXT.md` at the repository root when it exists.
- Read relevant ADRs under `docs/adr/` when that directory exists.
- If either location is absent, proceed silently. Domain-modeling work creates it lazily when terminology or a durable architectural decision is resolved.

## Layout

```text
/
|-- CONTEXT.md
|-- docs/
|   `-- adr/
`-- ai/ and agent-core/
```

## Vocabulary

Use terms defined by `CONTEXT.md` in issue titles, specifications, tests, and implementation. If a needed concept is absent, either reconsider the new term or record the gap for domain modeling.

## ADR conflicts

Surface conflicts with existing ADRs explicitly rather than silently overriding them.
