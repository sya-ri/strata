---
name: strata
description: Author, refactor, or review declarative Minecraft screens built with the Strata API. Use for component choice, structural layout, modifiers, state, inventory bindings, resource-pack assets, scrolling, and downstream custom components; do not use for unrelated Fabric or vanilla screen code.
license: MIT
---

# Strata

Build reusable Minecraft UI definitions against `strata-api`, then rely on the separately installed version-matched Fabric runtime to render and open them.

## When to use

Use this skill when application or Mod code needs to create, restructure, or review a Strata screen, select a standard component or modifier, bind slots or resources, or define a downstream component.

## Consumer scope

Default to consumer authoring through `dev.s7a.strata.component`, `modifier`, `layout`, `resource`, and `screen` APIs.
Do not import `runtime` packages, expose `MinecraftUiContext`, add `buildUi`, or construct a version-specific Minecraft screen in ordinary application UI code.
Only read Strata's `Element` and `Node` SPI when the request explicitly needs retained behavior that composition cannot express.

## Workflow

1. Read [setup.md](references/setup.md) to select one matching runtime and open an API-only `ScreenDefinition`.
2. Read [components.md](references/components.md) when choosing component overloads and ownership boundaries.
3. Read [modifiers-and-layout.md](references/modifiers-and-layout.md) for exact Modifier, parent-scope, state, and binding signatures.
4. Read [patterns.md](references/patterns.md) when structuring a full screen, scrolling, resources, or inventory bindings.
5. Read [custom-components.md](references/custom-components.md) before adding a component or retained primitive.

Prefer `Row`, `Column`, and `Grid` structure with arrangement, alignment, spacing, weight, and small local padding.
Use `Stack` only for intentional overlap.
Keep events on modifiers, mutable values in caller-owned state, and platform work behind the installed runtime.

## Output expectations

Return API-only UI definitions that compile without runtime imports, use exact public signatures from the references, and explain any fixed geometry or padding of 20 or more.
When reviewing a proposed Strata built-in, explicitly decide whether it is too purpose-specific and whether existing primitives already compose into it.
