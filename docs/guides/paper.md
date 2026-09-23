# Open screens from Paper

This guide describes the development sources.
For a source build, build the server plugin and version-matched Fabric runtime from the same revision using the [build guide](../development/build.md#loaded-client-verification).
For published artifacts, first check that the [matching release's documentation](https://github.com/sya-ri/strata/releases) includes this runtime and its required client support.

Install the `strata-runtime-paper` artifact with the `plugin` classifier in the server's `plugins` directory.
It contains Strata's shared runtime and Kotlin dependencies; Paper supplies its own API.
Players install the exact Minecraft version's Strata Fabric runtime and Fabric Language Kotlin as described in the [README](../../README.md#installation).
Use the same Strata release on both ends.
Vanilla clients cannot render these screens.

Compile a consuming plugin against `dev.s7a.strata:strata-runtime-paper:<strata-version>` and the Paper API with `compileOnly` dependencies.
Declare `depend: [Strata]` in its `plugin.yml`; do not shade another Strata copy into the consumer.
The [example build](../../examples/paper/build.gradle.kts), [plugin descriptor](../../examples/paper/src/main/resources/plugin.yml), and [plugin entry point](../../examples/paper/src/main/kotlin/dev/s7a/strata/examples/paper/PaperDemoPlugin.kt) are compiled together.

Call `PaperScreens.capabilities(player)` on Paper's primary thread before opening a screen.
A null result means negotiation has not completed or the client is unavailable.
Create independent state outside the DSL callback, construct a fresh `ScreenDefinition`, and pass it to `PaperScreens.open(ownerPlugin, player, definition)`.
The returned `RemoteScreenSession` exposes its identity and `RemoteSessionStatus`; `close()` releases its server-owned handlers and observations.
Unsupported declarations return a terminal reason rather than dropping parts of the screen.
Handlers may open another screen or close their current handle.
The service applies those transitions after the handler returns, preserving the core session's non-reentrant input boundary; a replacement handle remains `Opening` until that boundary completes.

The [compiled counter screen](../../examples/paper/src/main/kotlin/dev/s7a/strata/examples/paper/PaperDemoScreens.kt) combines a server-owned text field, counter, and button handler.
Build `:runtime:paper:pluginJar :examples:paper:jar`, install both artifacts, and use `/strata-demo` after joining with the matching client.
The example's JVM test evaluates and updates its actual screen factory through the public remote API.

Use `onActivate { ... }` for ordinary button actions.
For typed notifications, choose an explicit local propagation policy: `onKeyPress(propagation = InputResult.Consumed, filter = KeyboardInputFilter(setOf(KeyCode.Enter))) { event -> ... }` runs the handler on Paper while the client immediately consumes matching Enter presses.
The [compiled input screen](../../examples/paper/src/main/kotlin/dev/s7a/strata/examples/paper/PaperInputScreens.kt) also demonstrates button filtering, pointer coordinates, and preedit notifications.
Available subscriptions include key press/release, character/preedit input, pointer press/release/move/drag/scroll, and fixed-button capture with cancellation.
Unsubscribed variants and nonmatching keys/buttons are not transmitted.
Use `InputResult.Ignored` when the existing client control should continue processing the input, such as observing text-field composition.
The overloads return no handler result: the declared propagation/capture policy is already applied locally when the server receives the notification.
Event-dependent synchronous decisions require an installed client extension, as specified in the [remote protocol](../reference/remote-protocol.md#editing-and-local-behavior).

Paper executes declaration evaluation and accepted actions on its primary thread.
External state sources may notify from other threads; the shared session queues those revisions and commits them at its next cutoff.
Keep database and network work asynchronous and publish its result through a state source instead of blocking a handler.
Folia is outside this runtime's contract.
For proxy-owned screens and backend coexistence, use the separate [Velocity runtime](velocity.md).

Resource identifiers, fonts, and player skins resolve against the installed client's resources.
CPU Canvas snapshots and ready tiles transfer immutable pixels; native Canvas implementations must be installed and registered on the client.
Virtual lists retain models and row factories on the server and transfer only the current visible/overscan window.
Slot bindings address the player's existing native container, and item movement uses normal Minecraft container packets.
Opening or closing a native inventory retires the associated remote screen; clients reject Slot transactions after their captured native menu has changed.

Register custom schemas with `PaperScreens.register(ownerPlugin, projectionType)` before players negotiate.
The client installs its decoder/factory in `FabricRemoteScreens.registry` before its first connection freezes registration.
Schema versions and namespaces must match exactly; existing connections must reconnect to use newly registered server types.
Disabling an extension owner closes any current screen requiring its schema, including screens owned by another plugin.
The [declaration projection SPI](../reference/declaration-projection.md) and [remote protocol](../reference/remote-protocol.md) define typed properties, events, synchronous client behavior, and lifecycle requirements.

The [compatibility table](../reference/compatibility.md) distinguishes available exact Paper distributions from client targets.
A client compilation or common JVM test does not establish a successful Paper/native pair.
