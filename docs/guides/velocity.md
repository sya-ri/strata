# Open screens from Velocity

This guide covers the development sources; use the [matching release documentation](https://github.com/sya-ri/strata/releases) for published artifacts.

## Installation

Install `strata-runtime-velocity` with the `plugin` classifier in Velocity's `plugins` directory.
Players need their Minecraft version's Strata Fabric runtime and Fabric Language Kotlin.
Use the same Strata release on both ends, or build both from the same revision using the [build guide](../development/build.md#loaded-client-verification).
Backends need the [Paper runtime](paper.md) only when they also own screens.

Compile a consumer against `dev.s7a.strata:strata-runtime-velocity:<strata-version>` and Velocity API using `compileOnly` dependencies.
Declare a required dependency on plugin ID `strata` and do not bundle another Strata runtime.
See the [example build](../../examples/velocity/build.gradle.kts) and [plugin descriptor](../../examples/velocity/src/main/resources/velocity-plugin.json).
Build `:runtime:velocity:pluginJar :examples:velocity:jar`, install both artifacts, and run `/strata-proxy-demo` with the matching client.

## Definitions and state ownership

Strata owns a dedicated UI thread for definitions, state access, and handlers.
Pass a factory to `VelocityScreens.open(ownerPlugin, player) { ... }`, creating the `ScreenDefinition` and owner-thread state inside it.
The [counter example](../../examples/velocity/src/main/kotlin/dev/s7a/strata/examples/velocity/VelocityDemoScreens.kt) shows the DSL and button handlers.
The returned future supplies a `RemoteScreenSession`; read its detached status from any thread and call `close()` to queue cleanup.
Unavailable clients or unsupported declarations produce a terminal reason.

Use `VelocityScreens.capabilities(player)` to read negotiated capabilities asynchronously; null means negotiation is incomplete or the connection is unavailable.
Queue short external state updates with `VelocityScreens.execute(ownerPlugin) { ... }`.
Perform blocking work elsewhere, and never join a UI future from a handler or completion callback.

## Coexistence and lifecycle

Only one remote screen is visible: opening from Paper or Velocity closes the previous screen.
Switching backends retires native-container bindings and requires fresh negotiation.
Slots use the client's existing backend-managed container; Velocity does not create server inventories.
No Minecraft protocol translation or nested-proxy integration is provided.
The [remote protocol](../reference/remote-protocol.md) defines routing, resources, input propagation, and connection lifetimes.

Register extensions with `VelocityScreens.register(ownerPlugin, projectionType)` before negotiation and install matching client factories.
Missing capabilities reject the screen.
Call `VelocityScreens.release(ownerPlugin)` when a consumer stops its service early; proxy shutdown releases all owners automatically.
