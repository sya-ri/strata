# Open screens from Velocity

Install `strata-runtime-velocity` with the `plugin` classifier in Velocity's `plugins` directory.
It bundles Strata's common runtime and Kotlin dependencies; Velocity supplies its API and logging services.
Players install their Minecraft version's Strata Fabric runtime and Fabric Language Kotlin as described in the [README](../../README.md#installation).
Use the same Strata release on both ends.
The proxy may open screens without Strata installed on its backend servers.
Install the [Paper runtime](paper.md) on a backend when that server should also own screens.

Compile a consumer against `dev.s7a.strata:strata-runtime-velocity:<strata-version>` and Velocity API using `compileOnly` dependencies.
Declare a required dependency on plugin ID `strata`; do not package a second Strata runtime in the consumer.
The [example build](../../examples/velocity/build.gradle.kts), [plugin descriptor](../../examples/velocity/src/main/resources/velocity-plugin.json), and [entry point](../../examples/velocity/src/main/kotlin/dev/s7a/strata/examples/velocity/VelocityDemoPlugin.kt) compile together.
Build `:runtime:velocity:pluginJar :examples:velocity:jar`, install both artifacts, and use `/strata-proxy-demo` after joining with the matching client.

## Definitions and state ownership

Velocity has no main thread, so Strata owns a dedicated UI thread for definition construction, state access, declaration evaluation, and handlers.
Pass a factory to `VelocityScreens.open(ownerPlugin, player) { ... }`, constructing the `ScreenDefinition` and its `TextFieldState` or other owner-thread state inside that factory.
The [compiled counter](../../examples/velocity/src/main/kotlin/dev/s7a/strata/examples/velocity/VelocityDemoScreens.kt) uses the same DSL and `onActivate` handlers as a local or Paper screen.
The returned future completes with a `RemoteScreenSession`; unavailable clients and unsupported declarations return a terminal handle with a typed reason.
Read its detached `status` from any thread and call `close()` to queue terminal cleanup on the UI thread.

`VelocityScreens.capabilities(player)` asynchronously reads the completed handshake, returning null before negotiation, during backend replacement, or after disconnect.
`VelocityScreens.execute(ownerPlugin) { ... }` queues a short state update or read from an external event.
Perform blocking database or network work elsewhere, then publish its result through a state source or queue the final UI state change with `execute`.
Do not join another UI future from a handler or a completion callback; completions may run on the UI thread.
The request queue is bounded, and rejected requests fail explicitly instead of being silently dropped.

## Coexistence and lifecycle

Paper and Velocity negotiate independent capabilities and connection incarnations over the native Strata channel.
Only one remote screen is visible; opening a screen from either host closes the previous visible screen through its owning connection.
Switching backend servers retires the proxy screen's native-container binding and initiates fresh Paper and Velocity handshakes.
Both routes use new transport incarnations because native switching can discard in-flight plugin messages.
Old backend frames cannot execute operations on a new backend even when their screen and action numbers coincide.
Proxy-side routing rejects backend packets claiming the proxy endpoint, and ordered bounded queues preserve fragment and operation order across asynchronous event delivery.
No Minecraft protocol translation or nested-proxy integration is provided.

Slot components display the client's existing backend-managed container.
Item movement still uses the backend's normal Minecraft container protocol; Velocity does not provide a Bukkit inventory API or create server containers.
Resources, native Canvas implementations, fonts, and rendering capabilities resolve through the installed client exactly as described in the [Paper guide](paper.md).

Register extension schemas with `VelocityScreens.register(ownerPlugin, projectionType)` before client negotiation.
The client must install its matching factories; missing capabilities reject the whole screen.
Typed input subscriptions use the same [event and propagation contract](../reference/remote-protocol.md#editing-and-local-behavior) as Paper.
`VelocityScreens.release(ownerPlugin)` closes that consumer's screens and withdraws its extensions when it stops its service early.
Proxy shutdown automatically releases all owners, observations, queued requests, packet fragments, and the UI worker.
