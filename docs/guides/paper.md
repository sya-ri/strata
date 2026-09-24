# Open screens and HUDs from Paper

This guide covers the development sources; use the [matching release documentation](https://github.com/sya-ri/strata/releases) for published artifacts.

## Installation

Install `strata-runtime-paper` with the `plugin` classifier in the server's `plugins` directory.
Players need the exact Minecraft version's Strata Fabric runtime and Fabric Language Kotlin; vanilla clients cannot render these screens.
Use the same Strata release on both ends, or build both from the same revision using the [build guide](../development/build.md#loaded-client-verification).
The [compatibility reference](../reference/compatibility.md) lists available client artifacts and Paper distributions.

Compile a consumer against `dev.s7a.strata:strata-paper-api:<strata-version>` and the Paper API with `compileOnly` dependencies.
Declare `depend: [Strata]` in `plugin.yml` and do not shade Strata into the consumer.
See the [example build](../../examples/paper/build.gradle.kts) and [plugin descriptor](../../examples/paper/src/main/resources/plugin.yml).

## Open a screen

Call `PaperUi.capabilities(player)` on Paper's primary thread; null means the client is unavailable or negotiation is incomplete.
Import `dev.s7a.strata.paper.open`, create independent state outside the DSL callback, and call `UiDefinition(...).open(ownerPlugin, player)`.
The returned `UiSession` exposes `presentation`, `status`, `switch(...)`, input controls, and `close()`.
Choose `presentation = UiPresentation.Hud` on the definition to open an overlay.
Remote presentation is initially null and changes after the client applies it; unsupported declarations produce a terminal reason.

The [counter example](../../examples/paper/src/main/kotlin/dev/s7a/strata/examples/paper/PaperDemoScreens.kt) combines a text field, counter, and button handler.
Build `:runtime:paper:pluginJar :examples:paper:jar`, install both artifacts, and run `/strata-demo` with the matching client.

## Input and state

Use `onActivate { ... }` for button actions.
Its receiver is the owning `UiSession`, so `close()` and `switch(UiPresentation.Hud)` control the UI delivering that event.
Typed input subscriptions declare their propagation policy before the event; event-dependent synchronous decisions require a client extension.
The [input example](../../examples/paper/src/main/kotlin/dev/s7a/strata/examples/paper/PaperInputScreens.kt) demonstrates filtering and notifications; the [remote protocol](../reference/remote-protocol.md#editing-and-local-behavior) defines their contract.

Paper evaluates declarations and runs handlers on its primary thread.
Keep database and network work asynchronous and publish results through a state source.
Folia is outside this runtime's contract; use the [Velocity runtime](velocity.md) for proxy-owned screens.

## Resources and extensions

Resources and native rendering capabilities come from the installed client.
Slots use the player's existing native container; changing it retires ordinary remote screens and HUDs bound to that container, while independent HUDs remain open.
See the [remote protocol](../reference/remote-protocol.md) for resource, container, and lifecycle constraints.

Register schemas with `PaperUi.register(ownerPlugin, projectionType)` before negotiation and install matching client factories in `FabricRemoteScreens.registry` before the first connection.
Schema changes require reconnecting; disabling an extension owner closes screens that depend on it.
The [declaration projection SPI](../reference/declaration-projection.md) defines custom component and event contracts.

## Platform events and migration

Listen to the events in `dev.s7a.strata.paper.event` with ordinary Bukkit listeners.
They report client readiness, connection end, and applied UI opening, presentation changes, and closure on the primary thread.
The [platform event contract](../reference/platform-events.md) defines notification order and ownership.
Use the [shared UI guide](screens-and-state.md) for categories, visibility, interaction, and game input policies.

`ScreenDefinition`, `Screens.open(...)`, and `PaperScreens.open(...)` remain deprecated compatibility entries.
New consumers need only `paper-api`; do not bundle the API or depend on implementation classes to handle lifecycle events.
