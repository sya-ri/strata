# Open screens and HUDs from a Fabric server

This guide covers development sources; use the matching release documentation for installed artifacts.

## Installation

Install the Strata runtime matching the exact Minecraft version, Fabric API, and Fabric Language Kotlin on both the Fabric server and every client using the UI.
The same versioned Strata Mod supports dedicated servers, integrated singleplayer/LAN servers, and clients.
Vanilla clients may join, but cannot display Strata interfaces.

A consumer Mod compiles against `strata-api` and the version-matched Fabric runtime without bundling either.
Use the target's Loom dependency configuration (`modCompileOnly` for remapped targets, `compileOnly` for unobfuscated targets), and declare Strata as a required Mod dependency.
Keep both ends on the same Strata release.

Import `dev.s7a.strata.runtime.minecraft.fabric.Strata` as the platform entry point.

## Definitions and ownership

Call `Strata.open(ownerMod, player) { UiDefinition(...) { ... } }` on the native server thread after `ServerLifecycleEvents.SERVER_STARTED`.
`ownerMod` is the consuming Mod's `ModContainer`, and `player` is the actual connected `ServerPlayer`.
Create mutable state inside the factory, outside the definition's reevaluated content.
The [compiled counter example](../../integration/shared/minecraft-fabric/transport/verification/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/FabricServerUiExample.kt) demonstrates server-owned state and button callbacks.

The returned `UiSession` supports Screen/HUD presentation, visibility, game input controls, and closure through the common UI API.
Definitions and handlers execute on the server; the installed client performs layout, rendering, and immediate input.
`Strata.execute(server) { ... }` synchronously validates ownership for later state or session access.
Async callers must first schedule onto the native server thread; the method does not schedule or block waiting for another thread.

## Readiness, extensions, and lifecycle

`Strata.capabilities(player)` is null until successful negotiation or after the connection ends.
Register `Strata.listen(server, ownerMod) { event -> ... }` from `ServerLifecycleEvents.SERVER_STARTED` to receive typed readiness, disconnection, opening, presentation-change, and closure notifications.
Notifications execute on the server owner and cannot cancel committed transitions.
Opening and presentation-change notifications follow client acknowledgement; opening an API handle alone does not prove native application.
Close the returned subscription on the server thread when it is no longer needed.

Register projection schemas with `Strata.register(server, ownerMod, projectionType)` before players negotiate, normally from `SERVER_STARTED`.
Install matching client factories in `FabricRemoteScreens.registry` during client initialization.
Existing connections must reconnect to negotiate added schemas.
The [declaration projection contract](../reference/declaration-projection.md) describes custom elements, modifiers, and action bindings.

`Strata.release(server, ownerMod)` closes owned UIs, withdraws schemas, and removes that owner's subscriptions.
Server shutdown automatically releases every owner and all player transports.
Each integrated server has an independent lifetime, including when singleplayer worlds are opened again in one client process.
Native container changes retire ordinary remote Screens and Slot-bound HUDs; independent HUDs remain open.

Fabric play disconnection retires the current UI session even when the underlying socket continues through configuration.
A new play connection negotiates afresh; applications reopen UIs after readiness rather than retaining a session across configuration.
Paper/Folia and Velocity hosts use the same remote protocol and can coexist with a Fabric backend.
See [screens and state](screens-and-state.md), [platform events](../reference/platform-events.md), and the [remote protocol](../reference/remote-protocol.md) for the shared contracts.
