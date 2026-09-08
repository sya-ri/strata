# Screens and state

A Strata screen combines a component tree with state owned by the application.
Declare that tree with `ScreenDefinition`, then open it through the installed runtime on its owner thread.
The [README opening example](../../README.md#api-only-open-example) compiles against the API alone; the matching Fabric runtime supplies presentation.

## Declare and open a screen

`ScreenDefinition` retains its title, pause policy, and content callback without evaluating the callback.
The callback emits exactly one root, usually a layout container with child components.
The runtime installs its selected profile before evaluating the callback, so ordinary components and profile-backed background modifiers need no explicit Minecraft context or separate root builder.
Create a new definition for each opening.

`open()` is synchronous and does not switch threads.
A missing runtime or rejected calling thread leaves ownership with the caller, who may retry or close the definition.
After transfer, the runtime owns cleanup even if opening fails.
Closing an untransferred definition releases its captured values; closing after transfer has no effect on the active screen.
Transfer and close are atomic, and a definition cannot transfer twice.

The callback's `UiScope` exists only for that invocation and thread.
Do not retain it for later emission.
A callback failure propagates before root-cardinality validation, while zero or multiple roots fail before a retained tree is created.

## Own component state

Create state such as `TextFieldState`, `TextAreaState`, `ScrollState`, and `PanZoomState` on the thread that will host the screen.
Keep the state for as long as the application needs its value, and pass the same instance to components that deliberately share it.
The [compiled application screen](../../integration/api/src/main/kotlin/dev/s7a/strata/integration/consumer/ApiOnlyScreenDefinition.kt) creates a field state and a scrolling state before returning its definition.

Components observe their state through their own contracts; construction of a screen definition does not make arbitrary captured Kotlin values reactive.
Use the state operations exposed by each component, and perform application changes in event handlers rather than during declaration, measurement, layout, or painting.
Consult the [API reference](https://gh.s7a.dev/strata/) for each state's ownership and update methods.

A `ScrollArea` owns viewport geometry and scroll behavior through its `ScrollState`.
An independently placed `Scrollbar` can observe that state to expose a track and draggable thumb, or the application can omit it.
`TextAreaState` owns a stable scrolling state for its editor; [text editing](../guides/text.md#multiline-editing) explains that attachment and cursor behavior.
`PanZoomState` similarly belongs to one geometry-owning viewport and can have independent observing controls; see [tiled images](../guides/tiled-images.md).

For external asynchronous observations, use the [StateSource contract](../reference/state-sources.md) at components that accept a source, such as [Canvas](canvas.md) and [TiledImage](../guides/tiled-images.md).
Source callbacks may arrive on other threads, but retained source consumers commit observations through owner-thread frames.
The internal runtime session's local delegates and coroutine scope are implementation facilities, not an application screen API.

## Add actions and focus

`Button` and `Tab` supply appearance and enabled semantics.
Compose `onActivate(enabled)` for an action shared by primary pointer presses and focused Enter or Space presses, using the same enabled value for appearance and behavior.
Use `onPress` when an action is specifically a pointer operation.
The [modifier guide](modifiers.md) covers propagation, keyboard focus, traversal, and captured gestures.

## Connect Minecraft resources

Use `ResourceId` to identify resource-pack content without retaining a resource manager or mapped Minecraft object in application code.
Client and server code may share that identifier; the versioned client resolves pixels from its active resource stack.
An `Image` can display the complete immutable asset or a contained source rectangle at an independently declared destination size.
The first admitted resolution remains fixed for that host's lifetime, so create a new host to reliably pick up replacement resource pixels.
The [resource-image cache contract](../development/performance.md#minecraft-resource-image-resolution-identity) describes admission and ownership.

`PlayerHead` displays a detached skin snapshot, including its optional hat layer, rather than retaining a player or native texture.
Live `Slot` bindings address `Slots.playerInventory(index)`, logical `Slots.container(index)`, or `Slots.activeMenu(index)` and delegate transactions to the active server menu.
The active-menu form is the raw-index escape hatch.
Slot owns an 18 by 18 pointer region, optional 16 by 16 content, and background, content, and foreground hover layers.
The container-background modifier supplies row-dependent chest geometry and texture regions independently of slot behavior.
Native item rendering is unavailable to portable-only hosts; a Slot with ordinary component content remains suitable for headless rendering.
See the [component overview](../reference/components.md) and [inventory example](../examples/screens.md) for compiled compositions.
