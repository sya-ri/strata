# Open screens and HUDs from Velocity

This guide covers the development sources; use the [matching release documentation](https://github.com/sya-ri/strata/releases) for published artifacts.

## Installation

Install `strata-runtime-velocity` with the `plugin` classifier in Velocity's `plugins` directory.
Players need their Minecraft version's Strata Fabric runtime and Fabric Language Kotlin.
Use the same Strata release on both ends, or build both from the same revision using the [build guide](../development/build.md#loaded-client-verification).
Backends need the [Paper runtime](paper.md) only when they also own screens.

Compile a consumer against `dev.s7a.strata:strata-velocity-api:<strata-version>` and Velocity API using `compileOnly` dependencies.
Declare a required dependency on plugin ID `strata` and do not bundle another Strata runtime.
See the [example build](../../examples/velocity/build.gradle.kts) and [plugin descriptor](../../examples/velocity/src/main/resources/velocity-plugin.json).
Build `:runtime:velocity:pluginJar :examples:velocity:jar`, install both artifacts, and run `/strata-proxy-demo` with the matching client.

## Definitions and state ownership

Strata owns a dedicated UI thread for definitions, state access, and handlers.
Pass a factory to `VelocityUi.open(ownerPlugin, player) { ... }`, creating the `UiDefinition` and owner-thread state inside it.
The [counter example](../../examples/velocity/src/main/kotlin/dev/s7a/strata/examples/velocity/VelocityDemoScreens.kt) shows the DSL and button handlers.
The returned future supplies a `UiSession`; queue reads of its live properties and calls to `switch(...)` or `close()` with `VelocityUi.execute(ownerPlugin) { ... }`.
Inside a UI event callback, `this` is that session and operations already run on the owner thread.
The extension `definition.open(ownerPlugin, player)` also returns a future, but any captured owner-thread state must already belong to the UI thread.
Unavailable clients or unsupported declarations produce a terminal reason.

Use `VelocityUi.capabilities(player)` to read negotiated capabilities asynchronously; null means negotiation is incomplete or the connection is unavailable.
Queue short external state updates with `VelocityUi.execute(ownerPlugin) { ... }`.
Perform blocking work elsewhere, and never join a UI future from a handler or completion callback.

## Coexistence and lifecycle

One remote foreground Screen can coexist with multiple HUDs owned by Paper or Velocity.
Opening a foreground Screen replaces the previous remote foreground Screen; HUD visibility follows the definition's rules.
Switching backends retires native-container bindings and requires fresh negotiation.
Slots use the client's existing backend-managed container; Velocity does not create server inventories.
No Minecraft protocol translation or nested-proxy integration is provided.
The [remote protocol](../reference/remote-protocol.md) defines routing, resources, input propagation, and connection lifetimes.

Register extensions with `VelocityUi.register(ownerPlugin, projectionType)` before negotiation and install matching client factories.
Missing capabilities reject the screen.
Call `VelocityUi.release(ownerPlugin)` when a consumer stops its service early; proxy shutdown releases all owners automatically.

## Platform events and migration

Subscribe to the classes in `dev.s7a.strata.velocity.event` using Velocity's event manager.
They report negotiated client readiness, connection end, and proxy-owned UI opening, applied presentation changes, and closure.
Listeners run under Velocity's event execution model, so use detached event fields directly and queue live session access through `VelocityUi.execute`.
The [compiled plugin](../../examples/velocity/src/main/kotlin/dev/s7a/strata/examples/velocity/VelocityDemoPlugin.kt) handles failed opening without reading owner-thread state from a listener.
See the [platform event contract](../reference/platform-events.md) for order and ownership, and the [shared UI guide](screens-and-state.md) for HUD and input behavior.

`VelocityScreens.open(...)` remains a deprecated compatibility entry returning the older remote handle.
New consumers compile against `velocity-api` and use the common `UiSession` through `VelocityUi`.
