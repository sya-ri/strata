<div align="center">
  <img src="icon.svg" alt="Strata" width="112">
  <h1>Strata</h1>
</div>

Declarative Minecraft UI with reusable component trees, version-independent layout and state, and headless testing without launching Minecraft.

Strata is pronounced “STRAY-tuh” (`/ˈstreɪtə/`) and is the plural of *stratum*, meaning a layer.
The name reflects its layered design: declarative components, retained UI behavior, portable rendering, and environment-specific adapters.

Strata is under development; public artifacts are not available yet.
Features are documented as available only after executable tests verify them.

## Why Strata exists

Minecraft screens often combine layout, input handling, state changes, text resolution, game assets, and version-specific calls in one class.
That makes the result difficult to reuse and difficult to verify outside a running client.

The design separates those concerns into layers:

- application code declares components and owns application state;
- layout components measure and place their children from constraints instead of visual-tuning coordinates;
- retained nodes perform incremental measurement, layout, painting, input, semantics, and lifecycle work;
- active modifiers provide checked padding, size constraints, background painting, unresolved semantics, typed pointer/keyboard/text/focus actions, and typed layout parent data without changing component implementations;
- the retained core runtime emits draw commands and unresolved semantics on the JVM;
- the common Minecraft runtime owns one-shot screen definitions, immutable asset profiles, callback-scoped menu/container background modifiers and Slot/Text/TextField/Button/Scroll components, and a synchronous fixed-viewport host over the retained core;
- the latest Java release, Minecraft 26.2, has a Fabric boundary that extracts the supported native menu/container/slot/font/list/TextField profile and adapts common frames, typed mouse/keyboard/text input, and screen lifecycle on the client thread; loaded client GameTests verify exact native/Fabric/headless ARGB parity for `ConfirmScreen`, `DirectJoinServerScreen`, `ContainerScreen`, and `ObjectSelectionList` scenes.

The public element, node, and drawing contracts are designed for extension.
A custom primitive must work through those contracts without registering its concrete class in a central component dispatcher.

## Declarative root

The verified `buildUi` entry point synchronously collects exactly one caller-defined element without copying or registering its concrete type.
The callback scope is limited to the invoking thread and callback lifetime.

```kotlin
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element

fun buildScreen(root: Element): Element =
    buildUi {
        element(root)
    }
```

The external primitive integration test compiles this path and exercises the returned tree through measurement, layout, painting, input, semantics, and lifecycle cleanup.

<!-- strata-component-showcase:start -->
<!-- Generated file. Do not edit. -->

## Minecraft component showcase

This deterministic image is the actual 320 by 180 `ConfirmScreen` reconstruction from the frame that passed exact native-screen, Fabric-adapter, and headless comparison.

![Strata component showcase](docs/components/overview.png)

### Overview source

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.Button
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.menuBackground

/**
 * Builds the deterministic Minecraft 26.2 ConfirmScreen content used by the Fabric and headless parity paths.
 *
 * @return one-shot screen definition reproducing the native title, message, and button-row geometry.
 */
internal fun createConfirmScreenDefinition(): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Strata parity") {
        Box(
            modifier = Modifier.Empty.size(320, 180).menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                spacing = 8,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Text("Confirm action")
                Text("Continue with this action?")
                Row(
                    modifier = Modifier.Empty.padding(Insets(top = 16)),
                    spacing = 4,
                ) {
                    Button(
                        "Yes",
                        modifier = Modifier.Empty.onPress {},
                    )
                    Button(
                        "No",
                        modifier = Modifier.Empty.onPress {},
                    )
                }
            }
        }
    }
```

[Open the complete component showcase](docs/components.md)
<!-- strata-component-showcase:end -->

## Module layout

Modules enter the build only with working behavior and tests.
The dependency boundaries are:

- `api` is the platform-neutral public contract used by application and primitive code.
- `runtime/core` is configured as a publishable retained engine that measures, lays out, paints, dispatches input, and flattens unresolved semantics on the JVM. It has not been published to an external repository.
- `runtime/headless` is configured as a publishable headless adapter over the retained core.
  Its verified synchronous entry points render a positive fixed viewport into immutable scaled ARGB pixels, deterministic metadata-free RGBA8 PNG bytes, and logical unscaled semantics.
- `runtime/minecraft` is configured as a publishable Minecraft-independent one-shot screen definition, immutable profile, callback-scoped menu/container background modifiers, Slot/Text/TextField/Button/Scroll component set, and owner-thread host over the retained core.
  It applies every frame to exact fixed logical viewport constraints without exposing Fabric, resources, or mapped game types.
- `runtime/minecraft-fabric-26.2` is the client-only Java 25 version boundary for the latest Java release's resource, screen, rendering, and input adapter.
  It nests the common runtime jars in the mod artifact, keeps Minecraft types out of the common modules, and passes an exact loaded-game native/Fabric/headless pixel comparison.
- `integration/api` compiles and exercises a third-party primitive against the public contracts, retained core, and common Minecraft host.
- `integration/minecraft-fabric-26.2` runs the loaded client parity scenes against actual 26.2 resources, `ConfirmScreen`, `DirectJoinServerScreen`, and `ObjectSelectionList`; it is not published.
- `integration/docs` extracts the compiled parity-panel sources and synchronizes only images carrying the matching GameTest receipt; it is not published.

See [Architecture](docs/architecture.md) for dependency boundaries and extension rules, and [Element SPI](docs/element-spi.md) for the smallest complete custom primitive.

## Documentation

- [Architecture](docs/architecture.md) explains the public SPI, runtime boundaries, and testing strategy.
- [Built-in layout components](docs/layout.md) specifies Row, Column, Box, and Spacer measurement, arrangement, alignment, and weight behavior.
- [Component showcase](docs/components.md) contains every generated example and Minecraft-backed image from the exact native/Fabric/headless parity frame in one document.
- [Element SPI](docs/element-spi.md) explains node ownership, lifecycle, retained phases, and extension points.
- [Modifiers](docs/modifiers.md) explains active modifier nodes, typed parent data, positional reconciliation, lifecycle, and extension failures.
- [External state sources](docs/state-sources.md) specifies linearizable revisioned state observation across threads.
- [UI sessions](docs/ui-sessions.md) specifies retained state, frame cutoffs, coroutine generations, and failure handling inside the core runtime.
- [Build and release](docs/build.md) lists local quality checks and publication requirements.
- [Supporting a new Minecraft version](docs/minecraft-versions.md) defines the evidence, implementation, and compatibility process for another adapter.

## Build

Use the checked-in Gradle wrapper:

```shell
./gradlew check koverHtmlReport koverXmlReport -Pkover
```

## License

Strata is available under the [MIT License](LICENSE).
