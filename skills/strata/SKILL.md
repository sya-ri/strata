---
name: strata
description: Author, refactor, or review Strata Minecraft screens for Mods, Paper, and Velocity, and preview the same definitions with Web or Headless. Use for reactive UI, input subscriptions, and custom components; not unrelated web, platform, or vanilla screen code.
license: MIT
---

# Strata

Build reusable declarations against `strata-api` and choose the host before opening a screen.
Minecraft uses a separately installed Fabric runtime, with local, Paper, or Velocity state ownership.
Use Web and Headless to inspect those same screen definitions; their preview capabilities differ from the Minecraft runtime.

## Consumer scope

Use public component, modifier, layout, resource, and screen APIs.
Ordinary UI definitions must not import runtime packages, expose `MinecraftUiContext`, add `buildUi`, or construct a mapped screen.
Integration code uses public host entry points such as `PaperScreens` or `VelocityScreens`; preview harnesses may depend on Web or Headless runtime contracts.
Read setup before choosing dependencies or opening a screen, and reuse the application definition factory for previews instead of maintaining a separate preview UI.
Use Element/Node SPI only when composition cannot express the required retained behavior.
For reusable custom components, prefer a shared component module when reuse is needed; read custom components for what must match across server, client, and preview consumers.
If sharing a module is impractical, independent implementations are valid when their required wire contracts agree; do not require identical source or class names.

## Authoring decisions

- For application-owned values, retain `mutableStateOf` outside content; reads of `.value` during evaluation automatically track dependencies for ordinary Kotlin branches and loops.
  Dedicated control state also tracks value reads; passing an editor's state object alone does not subscribe the parent to every edit.
- Pass fixed values as literals and supported external `StateSource` arguments directly.
  Retain `source.map { ... }` outside reevaluation for displayed transformations.
- Use a narrow `Observe` for external-source-driven structural changes or unsupported layout/style inputs.
  Do not wrap a screen in Observe for independent labels, read snapshots into nonreactive literals, or recreate projections in callbacks.
- Keep editing, selection, cursor, scroll, and list-navigation state outside reevaluation.
  Use stable keys and `VirtualList` for large lists.
- Prefer layout spacing, arrangement, alignment, and weight to copied coordinates or large padding.
  Use Stack for intentional overlap.
- Default Text has natural single-line geometry.
  Use `TextLayout.Multiline()` for a reserved text rectangle, including a fixed-size Observe root.
- Put actions on modifiers and keep platform work behind the runtime.
  For custom editor colors, use `TextInputAppearance.Custom` on standard editors and retain the images, appearance, and state; see patterns for the complete setup.
- For remote screens, use typed subscriptions with declared filters and local propagation; only subscribed matching events reach server handlers.
  Keep state access on the host's UI owner thread and check negotiated capabilities before opening; see setup and modifiers.
  Custom synchronous input decisions and native drawing require an installed client implementation; see custom components.
- Verify clipping at the actual viewport/density, including fractional scaling.
  Cached callbacks do not imply free rendering: measure native composition and final pixels for frequently changing translucent layers.
- Report unsupported preview controls, assets, or native behavior explicitly; do not omit them or replace the screen with a browser-specific recreation and claim parity.
- When Web preview is requested, use a shared Kotlin Multiplatform screen project plus JVM host and JS preview projects that depend on it.
  Without Web preview, keep the existing Mod/plugin project structure; Headless tests alone do not require this split.

## Read for the current task

| Task | Reference |
| --- | --- |
| Fabric, Paper, or Velocity setup; Web and Headless previews | [setup.md](references/setup.md) |
| Choose a component or exact overload | [components.md](references/components.md) |
| Modifier, parent-scope, state, or binding signatures | [modifiers-and-layout.md](references/modifiers-and-layout.md) |
| Reactive screens, scrolling, resources, editor appearance | [patterns.md](references/patterns.md) |
| Downstream composition, retained primitives, or remote extensions | [custom-components.md](references/custom-components.md) |

Read reactive patterns before implementing a stateful screen; load other references as needed.
Keep reusable definitions API-only and put host integration at the opening boundary.
Use verified public signatures and explain fixed geometry only when it affects the design.
For a proposed standard built-in, explicitly assess excessive specialization and whether existing primitives can compose it.
Downstream application components are unrestricted by that admission gate.
