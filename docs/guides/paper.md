# Open screens from Paper

This guide covers the development sources; use the [matching release documentation](https://github.com/sya-ri/strata/releases) for published artifacts.

## Installation

Install `strata-runtime-paper` with the `plugin` classifier in the server's `plugins` directory.
Players need the exact Minecraft version's Strata Fabric runtime and Fabric Language Kotlin; vanilla clients cannot render these screens.
Use the same Strata release on both ends, or build both from the same revision using the [build guide](../development/build.md#loaded-client-verification).
The [compatibility reference](../reference/compatibility.md) lists available client artifacts and Paper distributions.

Compile a consumer against `dev.s7a.strata:strata-runtime-paper:<strata-version>` and the Paper API with `compileOnly` dependencies.
Declare `depend: [Strata]` in `plugin.yml` and do not shade Strata into the consumer.
See the [example build](../../examples/paper/build.gradle.kts) and [plugin descriptor](../../examples/paper/src/main/resources/plugin.yml).

## Open a screen

Call `PaperScreens.capabilities(player)` on Paper's primary thread; null means the client is unavailable or negotiation is incomplete.
Create independent state outside the DSL callback, construct a fresh `ScreenDefinition`, and call `PaperScreens.open(ownerPlugin, player, definition)`.
The returned `RemoteScreenSession` reports its status and provides `close()`; unsupported declarations produce a terminal reason.

The [counter example](../../examples/paper/src/main/kotlin/dev/s7a/strata/examples/paper/PaperDemoScreens.kt) combines a text field, counter, and button handler.
Build `:runtime:paper:pluginJar :examples:paper:jar`, install both artifacts, and run `/strata-demo` with the matching client.

## Input and state

Use `onActivate { ... }` for button actions.
Typed input subscriptions declare their propagation policy before the event; event-dependent synchronous decisions require a client extension.
The [input example](../../examples/paper/src/main/kotlin/dev/s7a/strata/examples/paper/PaperInputScreens.kt) demonstrates filtering and notifications; the [remote protocol](../reference/remote-protocol.md#editing-and-local-behavior) defines their contract.

Paper evaluates declarations and runs handlers on its primary thread.
Keep database and network work asynchronous and publish results through a state source.
Folia is outside this runtime's contract; use the [Velocity runtime](velocity.md) for proxy-owned screens.

## Resources and extensions

Resources and native rendering capabilities come from the installed client.
Slots use the player's existing native container; opening or closing an inventory retires the associated remote screen.
See the [remote protocol](../reference/remote-protocol.md) for resource, container, and lifecycle constraints.

Register schemas with `PaperScreens.register(ownerPlugin, projectionType)` before negotiation and install matching client factories in `FabricRemoteScreens.registry` before the first connection.
Schema changes require reconnecting; disabling an extension owner closes screens that depend on it.
The [declaration projection SPI](../reference/declaration-projection.md) defines custom component and event contracts.
