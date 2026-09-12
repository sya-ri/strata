# Screens and state

Declare a component tree with `ScreenDefinition`, keep its state in the application, and open it on the installed runtime's owner thread.
Start with the [compiled README example](../../README.md#api-only-open-example).

## Declare and open a screen

A definition retains its title, pause policy, and content callback until opened.
The callback emits exactly one root, usually a layout container.
The runtime supplies the active resource profile; application code needs no Minecraft context or separate root builder.

Create a new definition for each opening.
`open()` is synchronous and does not switch threads.
If the runtime is missing or rejects the calling thread, the caller may retry or close the definition.
Once ownership transfers, the runtime handles cleanup, including opening failures.
Closing an untransferred definition releases its captures; closing after transfer does not close the active screen.
Do not retain the callback's `UiScope` beyond that invocation.

## Own component state

Create editing and navigation states on the host thread and keep them outside callbacks that may be reevaluated.
Change application state in event handlers, not during declaration, measurement, layout, or painting.
Captured Kotlin variables are not automatically reactive.

| State | Use |
| --- | --- |
| `TextFieldState`, `TextAreaState` | Keep committed text and editing ownership. |
| `ScrollState` | Link a `ScrollArea` to an independently placed `Scrollbar`. |
| `TextAreaState.scrollState` | Add an external scrollbar to an editor. |
| `PanZoomState` | Control one [tiled-image viewport](tiled-images.md) and its observing controls. |

The [compiled application screen](../../integration/api/src/main/kotlin/dev/s7a/strata/integration/consumer/ApiOnlyScreenDefinition.kt) creates state before returning its definition.
See each state's [API contract](https://gh.s7a.dev/strata/) for attachment and update rules.

## Observe changing regions

Choose the smallest update boundary:

1. Pass a fixed value directly.
2. Pass a `StateSource` directly when the component supports it, such as `Text(source)`.
3. Retain `source.map { ... }` outside reevaluation for a displayed transformation.
4. Use `Observe` to add, remove, or switch children, or to change unsupported layout/style arguments.

Do not read a snapshot into a literal and expect observation, recreate mapped sources inside content, or wrap a whole screen in `Observe` for independent labels.
The [compiled reactive example](../../integration/docs/src/skillExamples/kotlin/dev/s7a/strata/integration/docs/skill/ReactiveScreenExample.kt) demonstrates these choices.
Direct inputs preserve the component's modifiers and compatible editing/focus/scroll state.

`Observe(source) { value -> ... }` is one layout child whose callback emits zero or one root.
Use an inner Row or Column for multiple children; apply parent weight and alignment to Observe itself.
An empty region has zero natural size, subject to its constraints.
Keep editable state outside its callback and never retain the callback scope or mutate sources there.
Typed overloads accept up to 22 sources in declaration order.

The tree shares subscriptions by source identity and commits pending values at frame boundaries.
Publications coalesce, equal values skip source-driven evaluation, and changed parents refresh child callbacks so captures stay current.
Compatible keys retain descendants; removed regions release observation.
Independent sources do not form a transaction: publish one immutable model when fields must change atomically.
See [external sources](../reference/state-sources.md) for subscription rules and [UI sessions](../development/ui-sessions.md#retained-observed-regions) for runtime ordering and cleanup.

## Add actions and focus

Use `onActivate(enabled)` for an action shared by primary pointer presses and focused Enter or Space presses.
Pass the same enabled value to the control and action modifier.
Use `onPress` for pointer-specific behavior; see [modifiers](modifiers.md) for focus and captured gestures.

## Connect Minecraft resources

Use `ResourceId` for resource-pack content without retaining mapped Minecraft objects.
`Image` can display a complete asset or a source rectangle at an independent destination size.
The first admitted resource-image resolution is fixed for the host lifetime; create a new host to pick up replacement pixels reliably.

`PlayerHead` uses a detached skin snapshot, including the optional hat layer.
Live `Slot` bindings use `Slots.playerInventory`, `Slots.container`, or raw-index `Slots.activeMenu` and delegate transactions to the active server menu.
Portable hosts can render ordinary Slot child content, but cannot draw native items.
See the [component overview](../reference/components.md) and [inventory example](../examples/screens.md) for compositions.
