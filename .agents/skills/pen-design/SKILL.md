---
name: pen-design
description: Create and update Finance Tracker UI designs in pen.dev. Use when planning or revising any user-visible page, navigation, form, dialog, responsive layout, or UI state in this repository.
---

# Finance Tracker Pen Design

Use pen.dev to create the project's authoritative visual design. Follow [`docs/shared/ui-design-rules.md`](../../../docs/shared/ui-design-rules.md) and the repository's `AGENTS.md`; those project rules take precedence over generic design defaults.

## Workflow

1. Read the UI rules, relevant OpenSpec change, existing `.pen` files, component library, and design tokens before designing. Preserve accepted product decisions and existing design-system constraints.
2. For an A-class UI change or major UI redesign, complete the Hallmark design-direction step first. Use its findings to shape the Pen work; Hallmark is the design method, while Pen is the visual source of truth.
3. Create or update the relevant file under `design/*.pen` with the official pen.dev CLI, Desktop, or MCP. Never hand-edit the `.pen` JSON, node IDs, or references. If the CLI is used, inspect its installed help for supported commands; do not install packages, change authentication, or create accounts as part of design work.
4. Keep one representative board for each applicable Compose `WindowSizeClass`: `COMPACT` (390 px), `REGULAR` (768 px), and `WIDE` (1440 px). Cover the core task and applicable normal, empty, error, focus, disabled, loading, and success states. Reuse the existing Pen component library and token source.
5. Treat the confirmed Pen frames as the sole source for page structure and visual properties. Record file path and frame/node IDs in the active OpenSpec `design.md`, along with the mapping needed for implementation.
6. For A-class UI changes and major UI redesigns, derive the change's standalone `prototype/index.html` from the confirmed Pen design. The HTML prototype validates interaction, keyboard paths, state changes, and responsive behavior; it must not become a competing visual specification. If prototype testing reveals a design problem, revise Pen first and then synchronize the prototype.
7. Present Pen and its matching interactive prototype together for user confirmation before implementation when the repository workflow requires confirmation. If scope or information architecture changes, update both artifacts and the OpenSpec design record.

## Tool availability

Use the official pen.dev CLI, Desktop, or MCP already available in the environment. If none is available, state which capability is missing and leave the design artifact unmodified; do not fabricate a `.pen` file or claim the design was generated. Keep credentials and authentication state outside the repository.
