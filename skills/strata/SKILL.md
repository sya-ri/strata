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

Choose the smallest automatic update boundary before writing a screen:

1. Pass fixed values as literals.
2. Pass an existing `StateSource` directly to a supported component argument.
3. For a displayed transformation, create and retain `source.map { ... }` outside reevaluated content, then pass that source directly.
4. Use a narrow `Observe` only for adding, removing, or switching children, or for unsupported layout/style arguments.
5. Keep editor, selection, cursor, and scroll state outside reevaluation. Never recreate them for a clock tick or new message.
6. Use `VirtualList` with stable item keys for large lists; keep its navigation state.

Do not wrap an entire screen in `Observe` for independent labels. Reading a snapshot into `Text(snapshot.value)` loses automatic observation. Recreating a mapped source inside observed content changes its identity and repeats binding/initial transformation work. Ordinary captured lambdas cannot be compared semantically: a parent content change also refreshes retained child definitions, even when the child's source is unchanged.
Read the reactive examples and counterexamples in [patterns.md](references/patterns.md) before implementing stateful screens. Updates should follow the caller's state; do not add screen close/reopen, `fromState`, or manual refresh for supported direct arguments.

1. Read [setup.md](references/setup.md) to select one matching runtime and open an API-only `ScreenDefinition`.
2. Read [components.md](references/components.md) when choosing component overloads and ownership boundaries.
3. Read [modifiers-and-layout.md](references/modifiers-and-layout.md) for exact Modifier, parent-scope, state, and binding signatures.
4. Read [patterns.md](references/patterns.md) when structuring a full screen, scrolling, resources, or inventory bindings.
5. Read [custom-components.md](references/custom-components.md) before adding a component or retained primitive.

Prefer `Row`, `Column`, and `Grid` structure with arrangement, alignment, spacing, weight, and small local padding.
Default `Text` is a natural-size single line: forcing a larger `size` or filled weight can violate its constraints. For a reserved clock/status rectangle, use `Text(layout = TextLayout.Multiline(), ...)`; this also applies to the single root inside a fixed-size Observe. A literal or reactive label follows the same geometry contract.
Use `Stack` only for intentional overlap.
Keep events on modifiers, mutable values in caller-owned state, and platform work behind the installed runtime.

## Output expectations

Return API-only UI definitions that compile without runtime imports, use exact public signatures from the references, and explain fixed geometry when the screen depends on it.
When reviewing a proposed Strata built-in, explicitly decide whether it is too purpose-specific and whether existing primitives already compose into it.
