---
name: strata
description: Author, refactor, or review declarative Minecraft screens with the Strata API. Use for component choice, layout, reactive state, resources, bindings, and custom components; not unrelated Fabric or vanilla screen code.
license: MIT
---

# Strata

Build application UI against `strata-api`; a separately installed, version-matched Fabric runtime presents it.

## Consumer scope

Use public component, modifier, layout, resource, and screen APIs.
Ordinary UI definitions must not import runtime packages, expose `MinecraftUiContext`, add `buildUi`, or construct a mapped screen.
Use Element/Node SPI only when composition cannot express the required retained behavior.

## Authoring decisions

- Pass fixed values as literals and supported `StateSource` arguments directly.
  Retain `source.map { ... }` outside reevaluation for displayed transformations.
- Use a narrow `Observe` for structural changes or unsupported layout/style inputs.
  Do not wrap a screen in Observe for independent labels, read snapshots into nonreactive literals, or recreate projections in callbacks.
- Keep editing, selection, cursor, scroll, and list-navigation state outside reevaluation.
  Use stable keys and `VirtualList` for large lists.
- Prefer layout spacing, arrangement, alignment, and weight to copied coordinates or large padding.
  Use Stack for intentional overlap.
- Default Text has natural single-line geometry.
  Use `TextLayout.Multiline()` for a reserved text rectangle, including a fixed-size Observe root.
- Put actions on modifiers and keep platform work behind the runtime.
  For custom editor colors, use `TextInputAppearance.Custom` on standard editors and retain the images, appearance, and state; see patterns for the complete setup.
- Verify clipping at the actual viewport/density, including fractional scaling.
  Cached callbacks do not imply free rendering: measure native composition and final pixels for frequently changing translucent layers.

## Read for the current task

| Task | Reference |
| --- | --- |
| Installation, runtime selection, opening | [setup.md](references/setup.md) |
| Choose a component or exact overload | [components.md](references/components.md) |
| Modifier, parent-scope, state, or binding signatures | [modifiers-and-layout.md](references/modifiers-and-layout.md) |
| Reactive screens, scrolling, resources, editor appearance | [patterns.md](references/patterns.md) |
| Downstream composition or retained primitives | [custom-components.md](references/custom-components.md) |

Read reactive patterns before implementing a stateful screen; load other references as needed.
Return API-only definitions using verified public signatures and explain fixed geometry only when it affects the design.
For a proposed standard built-in, explicitly assess excessive specialization and whether existing primitives can compose it.
Downstream application components are unrestricted by that admission gate.
