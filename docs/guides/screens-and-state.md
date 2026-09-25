# Screens and state

Declare a component tree with `UiDefinition`, keep its state in the application, and open it within the installed runtime's execution owner.
Start with the [compiled README example](../../README.md#api-only-open-example).

## Declare and open a screen

A definition retains its optional narration title, presentation, category, input policy, HUD visibility rules, pause policy, and content callback until opened.
Omitting `title` uses Strata's translatable generic narration name; it never adds a visible title element.
`presentation` defaults to `UiPresentation.Screen`, and `pausesGame` defaults to `false` and applies only to Screen presentation.
The callback emits exactly one root, usually a layout container.
The runtime supplies the active resource profile; application code needs no Minecraft context or separate root builder.

Create a new definition for each opening.
The local `open()` is synchronous and does not switch threads.
If the runtime is missing or rejects the calling thread, the caller may retry or close the definition.
Once ownership transfers, the runtime handles cleanup, including opening failures.
Closing an untransferred definition releases its captures; after opening, call the returned `UiSession.close()` to end the UI.
Paper uses the same definition and handle through the `paper-api` extension `definition.open(plugin, player)`; the plugin owns cleanup on disable.
Do not retain the callback's `UiScope` beyond that invocation.
On Folia, use `PaperUi.open` to create state inside the player's owner, and `PaperUi.execute` for later external state or session access from that player's region.
Use the host-specific opening boundary for [Paper and Folia](paper.md), [Velocity](velocity.md), or the [browser runtime](../../README.md#build-a-web-screen-from-source).

## Switch presentation and close

`session.switch(UiPresentation.Hud)` and `session.switch(UiPresentation.Screen)` preserve the session identity, retained content, text, and scroll values.
Switching releases focus, pointer capture, and forwarded game keys.
Switching the current Screen to HUD returns to the game; it does not close an unrelated native screen.
Promoting a HUD captures the usual Screen return target.

Every `onXxx` callback receives its owning `UiSession` as `this`:

```kotlin
UiDefinition(presentation = UiPresentation.Hud) {
    Column {
        Button("Open as screen", modifier = Modifier.Empty.onActivate {
            switch(UiPresentation.Screen)
        })
        Button("Close", modifier = Modifier.Empty.onActivate { close() })
    }
}.open()
```

The receiver belongs to the node delivering the event, including when one modifier is reused across UIs.
The [compiled HUD example](../../integration/api/src/main/kotlin/dev/s7a/strata/integration/consumer/ApiOnlyHud.kt) combines these callbacks with category rules and retained application state.
Controls requested in an event apply after event handling returns.
Repeated close is safe; close supersedes pending switches and a closed handle cannot reopen.
`presentation` is the last applied native presentation, initially `null`, and retains its last value after close.
For remote UIs it changes only after client application acknowledgement; that acknowledgement does not prove pixels were displayed.
`status` reports opening, ready, a pending switch, or a typed terminal reason.
An immediate unsupported request returns `UiOperationResult.Rejected`; asynchronous rejection is available through `UiSessionStatus.Ready.rejection`.

## Categorize screens and place HUDs

`UiCategory(ResourceId("example", "map"))` defines an optional category without registration.
A HUD's `UiVisibilityPolicy` chooses `KeepVisible`, `Hide`, or `Close` for another native screen.
Category overrides take precedence over `UiScreenKind` overrides, followed by the default rule.
Screen kinds are `Strata`, `Inventory`, `Chat`, `Pause`, and `Other`; the default action is `Hide`.
No ordinary screen means the HUD is visible, and its own switch or internal interaction screen is excluded.
Rules apply on opening, switching, and subsequent native screen transitions.

`Hide` retains content and remote updates while stopping rendering and input; closing the other screen restores visibility without restarting HUD interaction.
`Close` terminates ownership and never restores that session automatically.
HUDs render above vanilla HUD elements and below ordinary screens, ordered by ascending `hudOrder` then opening order, and follow F1 visibility.
A HUD containing native Slots is bound to the container generation those Slots first use; replacing that container closes that HUD while independent HUDs remain open.

## Control game input

Passive HUDs leave ordinary gameplay alone.
`setInteractionMode(UiInteractionMode.Cursor)` selects one HUD for interaction; selecting another ends the previous interaction.
Escape or `setInteractionMode(UiInteractionMode.None)` ends HUD interaction.
Starting HUD interaction while an ordinary screen is open is rejected.
There is no built-in activation keybinding.
`UiInteractionMode.Look` captures the mouse for camera control, subject to the input policy.

`UiInputPolicy.BlockAll` is the initial policy; `Movement` enables movement, jumping, sneaking, and sprinting, while `All` enables every supported game action.
Individual settings also control look, attack, use, hotbar selection, drop, swap hands, and pick.
`session.setInputPolicy(...)` changes them while open.
The adapter uses actual native key assignments and ordinary gameplay handling.
Consumed UI input is not forwarded a second time; text editing suspends all forwarding.
Focus loss, permission changes, input-owner changes, switching, and closing release held inputs.

The Web runtime accepts Screen presentation and explicitly rejects HUD and game input controls.
Its mounted host exposes the same handle through `WebUiHost.uiSession`.

## Compatibility entry points

Prefer `UiDefinition.open()` over `ScreenDefinition`, `Screens.open(...)`, `PaperScreens.open(...)`, and `VelocityScreens.open(...)`.
The older entry points delegate to the common UI path.
Rebuild consuming Mods and plugins after migrating callback types: a stored `() -> Unit` or bound method reference may need a receiver lambda such as `onActivate { handler() }`.
Use the callback's `this` for session operations and qualify an outer receiver explicitly when needed.
Keep event arguments and return values unchanged; synchronous remote input results still require a client implementation.

## Own component state

Create editing and navigation states inside the host's execution owner and keep them outside callbacks that may be reevaluated.
Change application state in event handlers, not during declaration, measurement, layout, or painting.
Captured Kotlin variables are not automatically reactive.

Use `mutableStateOf(initialValue)` from `dev.s7a.strata.state` for an application-owned value and `State<T>` for a read-only view.
Create it outside content inside the host's execution owner.
Reads of `.value` during evaluation track dependencies, so ordinary Kotlin `if`, `when`, loops, and called composition functions update when the value changes.
Equal assignments do not invalidate content; changed assignments coalesce until the next evaluation.
Only values read in the active branch remain dependencies, and reads exclusively inside event handlers do not subscribe content.
Use stable keys for reordered children and retain state outside branches when it must survive their removal.
The [compiled shared scenario](../../integration/web/src/commonMain/kotlin/dev/s7a/strata/integration/web/ReactiveScenario.kt) demonstrates conditional content and keyed reordering on Minecraft, Headless, and Web.

Dedicated control values such as `TextFieldState.value` also participate when read in content.
Passing the state object to its editor alone does not make the parent reevaluate on every edit.
For external publishers, use `StateSource` and the queued observation paths below; it is a different contract from owner-confined `State`.

| State | Use |
| --- | --- |
| `MutableState<T>` | Track application-owned values read during declaration evaluation. |
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
4. Use `Observe` for external-source-driven child changes or unsupported layout/style arguments; owner-thread `State.value` reads already track ordinary Kotlin declarations.

Do not read a snapshot into a literal and expect observation, recreate mapped sources inside content, or wrap a whole screen in `Observe` for independent labels.
The [compiled reactive example](../../integration/docs/src/skillExamples/kotlin/dev/s7a/strata/integration/docs/skill/ReactiveScreenExample.kt) demonstrates these choices.
Direct inputs preserve the component's modifiers and compatible editing/focus/scroll state.

`Observe(source) { value -> ... }` is one layout child whose callback emits zero or one root.
Use an inner Row or Column for multiple children; apply parent weight and alignment to Observe itself.
An empty region has zero natural size, subject to its constraints.
Keep editable state outside its callback and never retain the callback scope or mutate sources there.
Typed overloads accept up to 22 sources in declaration order.
Local state reads inside an Observe callback belong to that region, allowing it to refresh without reevaluating a clean root.

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
