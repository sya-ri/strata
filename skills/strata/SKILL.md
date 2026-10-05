---
name: strata
description: Author, refactor, or review Strata UI declarations, choosing standard components and modifiers, reusable content slots, reactive ownership, and host boundaries. Use for Minecraft screens and previews of the same definitions; not unrelated web or vanilla screen code.
license: MIT
---

# Strata

## Consumer scope

Write UI declarations against `strata-api`; keep host opening, platform services, and preview harnesses at their integration boundaries.
Use the same application definition factory for Minecraft, Web, and Headless.
Check the project's selected Strata version and its public signatures before writing code; do not invent overloads or use runtime implementation APIs in ordinary UI.

## Choose components and modifiers

- Start with the standard component that owns the required behavior. Use `Row` for horizontal siblings, `Column` for vertical siblings, `FlowRow` for wrapping siblings, `Grid` for repeated cells, and `Stack` for intentional overlap. A `Stack` is not a generic wrapper.
- Express sibling relationships with spacing, arrangement, alignment, and weight. Use padding for local insets, not copied coordinates or sibling positioning. Do not add a one-child layout when the child's modifier or the existing parent's alignment expresses the same result.
- Put a container's paint on its layout with a background modifier; use `Image` for an image that is itself a child. Keep clipping, hit areas, and modifier ordering deliberate.
- Modifier operations return an immutable chain. Pass, assign, or return the result; calling an operation without using its result changes nothing.
- Use parent-data modifiers such as `weight` and `align` only for the direct child of the scope that consumes them. Do not move them into a helper with an unrelated receiver.
- Put actions on modifiers. Prefer standard controls and their state/appearance APIs; retain a custom composition when a theme or input contract needs it. Supply semantics, focus, and keyboard activation as well as pointer behavior.
- Default `Text` has natural single-line geometry. Use `TextLayout.Multiline()` when reserving a text rectangle, including a fixed-size `Observe` root.
- Choose virtualization from the maximum real item count and row geometry. Use stable keys and `VirtualList` for large lists; a scroll container alone does not avoid constructing invisible items.
- Match explicit component sizes to their actual constraints. `VirtualList` and `SelectionList` require their declared `viewportSize` to fit; fixed-size controls such as `ProgressBar` also retain their `size` argument. `weight` and `fillMax*` do not rewrite those arguments. Derive geometry from the allocation or preserve matching fixed bounds, and render the minimum and maximum supported sizes.
- Check the selected profile's geometry limits as well as API signatures before deriving a control's dimensions from a resizable screen.

Read [components](references/components.md) and [modifiers and layout](references/modifiers-and-layout.md) for the exact signatures needed by the change, rather than loading the whole catalog by default.

## Compose reusable components

- Give each reusable component one UI responsibility. Extract repeated structure or behavior with a shared meaning; do not create a wrapper solely to rename a standard call or collect unrelated helpers.
- A shared frame owns its geometry, decoration, and common slots. Receive variable content as `UiScope.() -> Unit` or an appropriate scoped lambda. Keep application selection in the router and each application's UI in that application.
- Do not pass an application/page discriminator or the entire application client into a shared frame so it can implement several applications with branches. Loading, empty, error, and ready branches inside the application that owns those states are valid.
- Let the caller supply a `modifier` with `Modifier.Empty` as the default when the component has a modifiable root. Apply it once to that root, preserving order; use separate internal modifiers for children. Do not ignore it or distribute it across several children. Pass stable root keys when identity must survive reconciliation.
- Emit exactly one root in a `UiDefinition` and zero or one root in `Observe`. Use an intentional layout for sibling roots; layout content may emit multiple children.
- Compose public primitives first. Use the `Element`/`Node` SPI only for retained measurement, drawing, input, semantics, or lifecycle behavior that composition cannot express. Do not reimplement standard editing or drawing merely to change its appearance.
- Keep shared UI free of host opening and business-service access. Share a module when multiple consumers need the same component; an ordinary application-local composition needs no registration or new module.

See [custom components](references/custom-components.md) for compiled content-slot examples and the retained SPI boundary.

## Preserve state and update boundaries

- Give state an explicit owner and lifetime. Keep business/data updates and submitted values in the screen owner; keep UI-only editing mechanics, focus, and scrolling near the components that need them. Pass children the necessary values, retained state, and typed events rather than the whole controller. Split a state holder when responsibilities or lifetimes differ, not merely to increase the number of classes.
- Retain application `mutableStateOf`, editing, selection, cursor, scroll, and list-navigation state outside reevaluated content. Read `.value` during evaluation to track ordinary branches and loops; passing an editor state object alone does not subscribe its parent to every edit.
- Pass supported external `StateSource` arguments directly. Retain `source.map { ... }` projections outside reevaluation; do not replace changing sources with nonreactive snapshot literals or recreate projections in callbacks.
- Use a narrow `Observe` for external-source structural changes or unsupported layout/style inputs. Do not observe a whole screen for independent labels. Do not publish state, fetch resources, or perform I/O from declarative evaluation.
- Use managed source bindings for UI observation. Acquire manual subscriptions in their owner outside reevaluation and close their handles at the end of that owner's lifetime.
- Retain reusable images and appearance objects. Describe each cache's owner, key, invalidation, bound, and release path before adding it; caching a callback does not eliminate native composition work.
- Keep keys, the owning session, input state, and scroll anchors stable through data updates. Do not reopen the screen or rebuild the host to refresh content.

Read [patterns](references/patterns.md) when implementing reactive content, scrolling, resources, or editors.

## Review the resulting tree

Check the actual component tree, not just file names: the router selects applications, the frame receives slots, application components own their states, and shared components apply modifiers at the intended boundary.
Compare input, semantics, focus, scroll position, and clipping before and after a refactor at the real viewport and density, including fractional scaling.
Measure affected workloads with the shared Performance testkit, keeping resource preparation and screenshots outside timed work; verify idle reuse and update invalidation separately.
Report missing preview controls, assets, or native capabilities explicitly instead of replacing the UI and claiming parity.

Static checks cover only their documented syntactic or resolved-symbol contracts.
Responsibility boundaries, meaningful reuse, and suitability of a component still require reviewing real examples; do not ban every branch or every custom composition.
See the [authoring checks guide](https://github.com/sya-ri/strata/blob/master/docs/guides/authoring-checks.md) for the optional type-aware Detekt rules and their limits.

## Read only for the current task

| Task | Reference |
| --- | --- |
| Dependencies, Fabric/Paper/Velocity opening, Web or Headless setup | [setup](references/setup.md) |
| Component choice and exact overload | [components](references/components.md) |
| Modifier order, parent scopes, state/binding signatures | [modifiers and layout](references/modifiers-and-layout.md) |
| Reactive screens, lists, resources, custom editor appearance | [patterns](references/patterns.md) |
| Reusable content slots or a custom retained primitive | [custom components](references/custom-components.md) |
| A primitive with its own measurement, drawing, input, or lifecycle | [retained components](references/retained-components.md) |
| Remote custom projections, codecs, capabilities, input ownership | [remote extensions](references/remote-extensions.md) |
| A requested vanilla inventory theme | [inventory styling](references/inventory-style.md) |

Host-specific synchronous input and native drawing require the relevant installed implementation.
Keep state access on the host's UI owner thread and check negotiated capabilities before opening remote screens.
For a proposed Strata built-in, require one focused responsibility, two natural independent use cases, and behavior that existing primitives cannot sufficiently compose; application-specific components may remain downstream.
