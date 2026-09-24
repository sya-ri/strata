# Open screens from Paper and Folia

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

Call `PaperScreens.capabilities(player)` on Paper's primary thread or the player's owning Folia region; null means the client is unavailable or negotiation is incomplete.
Create independent state inside the factory passed to `PaperScreens.open(ownerPlugin, player) { ... }`, then return a fresh `ScreenDefinition`.
Keep the state outside that definition's DSL callback so reevaluation preserves it.
The factory runs immediately on the caller's region inside the player's UI owner.
The returned `RemoteScreenSession` reports its status and provides `close()`; unsupported declarations produce a terminal reason.

The [counter example](../../examples/paper/src/main/kotlin/dev/s7a/strata/examples/paper/PaperDemoScreens.kt) combines a text field, counter, and button handler.
Build `:runtime:paper:pluginJar :examples:paper:jar`, install both artifacts, and run `/strata-demo` with the matching client.

## Input and state

Use `onActivate { ... }` for button actions.
Typed input subscriptions declare their propagation policy before the event; event-dependent synchronous decisions require a client extension.
The [input example](../../examples/paper/src/main/kotlin/dev/s7a/strata/examples/paper/PaperInputScreens.kt) demonstrates filtering and notifications; the [remote protocol](../reference/remote-protocol.md#editing-and-local-behavior) defines their contract.

Paper evaluates declarations and runs handlers on its primary thread.
Folia uses the player's entity scheduler, so state, declarations, and handlers follow that player across region and physical-thread changes.
The same plugin jar supports both platforms; a consuming plugin must also declare `folia-supported: true` and obey [Folia's region ownership rules](https://docs.papermc.io/paper/dev/folia-support/).
A player's handler may access only world and entity state owned by its current region.
Schedule work for other entities through their own schedulers; do not synchronously wait for another region.

Keep database and network work asynchronous and publish results through a state source.
For direct updates to caller-retained UI state on Folia, first schedule onto the player with `player.scheduler`, then call `PaperScreens.execute(player) { ... }`.
`execute` is synchronous: it enters that player's UI owner but does not move the call onto a region.
Do not share owner-confined mutable UI state between players, or capture state constructed outside the player's factory or `execute` scope.
The overload accepting an already-created `ScreenDefinition` remains available when its captured state already belongs to the correct owner.

Close a session handle from the player's region; status remains readable from any thread.
Disconnect, entity retirement, and Strata shutdown release the screen and transport queues.
A dependent plugin's disable event withdraws its schemas immediately and retires affected screens before the next player-owned operation.
Terminal resource cleanup must not mutate world state: Folia can retire entities in a restricted callback or stop regions before plugin shutdown.

## Resources and extensions

Resources and native rendering capabilities come from the installed client.
Ordinary movement, including riding a minecart, keeps the screen open.
Slots use the player's existing native container; opening or closing an inventory retires the associated remote screen.
This also applies when teleportation closes the native inventory; after arrival, reopen a fresh definition on the player's region while retaining its caller-owned UI state.
See the [remote protocol](../reference/remote-protocol.md) for resource, container, and lifecycle constraints.

Register schemas with `PaperScreens.register(ownerPlugin, projectionType)` before negotiation and install matching client factories in `FabricRemoteScreens.registry` before the first connection.
Schema changes require reconnecting; disabling an extension owner closes screens that depend on it.
The [declaration projection SPI](../reference/declaration-projection.md) defines custom component and event contracts.
