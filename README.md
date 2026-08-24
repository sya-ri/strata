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
- the platform-neutral API owns one-shot screen definitions; Row/Column/Stack/Grid layout; Text, TextField, Button, Checkbox, CycleButton, Slider, Tab, ScrollArea, Scrollbar, VirtualList, SelectionList, Image, Slot, PlayerHead, LoadingIndicator, and ProgressBar authoring; resource identifiers; slot locators; skin sources; and active modifiers, so application source compiles without a runtime dependency;
- the common Minecraft runtime installs itself behind that API, resolves the selected profile and resources, synchronizes bound slots with the active server menu, and hosts the retained tree without exposing a context receiver to application code;
- the latest Java release, Minecraft 26.2, has a Fabric boundary that extracts the supported native profile, resolves Mod images and current-player skin pixels through the active resource and texture paths, and adapts common frames, typed mouse/keyboard/text input, and screen lifecycle on the client thread; loaded client GameTests verify exact native/Fabric/headless ARGB parity for vanilla screens, PlayerHead, and a primitive-composed Social Interactions screen, exact Fabric/headless parity for resource-pack-backed industrial and progression Mod screens, and live server-authoritative inventory interaction.

The public element, node, and drawing contracts are designed for extension.
A custom primitive must work through those contracts without registering its concrete class in a central component dispatcher.
Applications may also define purpose-specific components as ordinary compositions of public primitives.
Strata's own standard built-ins are limited to focused components with multiple natural uses, but that generality review does not restrict application or Mod components such as an energy gauge or social-entry row.

## Installation and API-only authoring

Public artifacts are not available yet.
From a source checkout, `./gradlew publishToMavenLocal` installs the current artifacts for local integration; application authoring needs only `strata-api` on its compile classpath.

```kotlin
dependencies {
    implementation("dev.s7a.strata:strata-api:0.1.0")
}
```

The selected host adapter is a runtime concern.

| Minecraft | Fabric runtime artifact | Required Java | Loaded verification |
| --- | --- | --- | --- |
| 26.2 | `strata-runtime-minecraft-fabric-26.2` | 25 | Exact native/Fabric/headless parity, Mod-screen parity, and synchronized inventory GameTests |
| 26.1 | `strata-runtime-minecraft-fabric-26.1` | 25 | The same loaded suite; every recorded ARGB hash matches 26.2 for the fixed verified scenes |
| 1.21.11 | `strata-runtime-minecraft-fabric-1.21.11` | 21 | Development and production-jar loaded-client verification for the shared remapped legacy boundary |
| 1.21.10 | `strata-runtime-minecraft-fabric-1.21.10` | 21 | Development and production-jar loaded-client verification for the shared remapped legacy boundary |
| 1.21.9 | `strata-runtime-minecraft-fabric-1.21.9` | 21 | Development and production-jar loaded-client verification for the shared remapped legacy boundary |
| 1.21.8 | `strata-runtime-minecraft-fabric-1.21.8` | 21 | Development and production-jar loaded-client verification for primitive screen input and resource-location player skins |
| 1.21.7 | `strata-runtime-minecraft-fabric-1.21.7` | 21 | Development and production-jar loaded-client verification for the shared primitive-input release family |
| 1.21.6 | `strata-runtime-minecraft-fabric-1.21.6` | 21 | Development and production-jar loaded-client verification for the shared primitive-input release family |
| 1.21.5 | `strata-runtime-minecraft-fabric-1.21.5` | 21 | Development and production-jar loaded-client verification for the pose-stack and render-type compatibility boundary |
| 1.21.4 | `strata-runtime-minecraft-fabric-1.21.4` | 21 | Development and production-jar loaded-client verification for the unnamed dynamic-texture compatibility boundary |
| 1.21.3 | `strata-runtime-minecraft-fabric-1.21.3` | 21 | Development and production-jar loaded-client verification for the direct player-skin future and pre-Client-GameTest boundary |
| 1.21.2 | `strata-runtime-minecraft-fabric-1.21.2` | 21 | Development and production-jar loaded-client verification for the shared direct-skin standalone-runner family |
| 1.21.1 | `strata-runtime-minecraft-fabric-1.21.1` | 21 | Development and production-jar loaded-client verification for legacy pixel, blit, Slot, Tooltip, and progress treatments |
| 1.21 | `strata-runtime-minecraft-fabric-1.21` | 21 | Development and production-jar loaded-client verification for the same legacy pixel, blit, Slot, Tooltip, and progress treatments |
| 1.20.6 | `strata-runtime-minecraft-fabric-1.20.6` | 21 | Development and production-jar loaded-client verification for the legacy resource-construction boundary and complete standalone suite |
| 1.20.5 | `strata-runtime-minecraft-fabric-1.20.5` | 21 | Development and production-jar loaded-client verification for the same legacy resource-construction boundary and complete standalone suite |
| 1.20.4 | `strata-runtime-minecraft-fabric-1.20.4` | 17 | Development and production-jar loaded-client verification for the legacy Java, GUI-asset, player-profile, and standalone-runner boundaries |

Select exactly one versioned Fabric runtime at execution time.
The version artifacts intentionally expose the same Strata-owned entry points and class names, while their inherited Minecraft `Screen` methods differ with the native release, so depending on more than one creates duplicate classes.
`ScreenDefinition` evaluates its callback after the Minecraft runtime has installed the active profile, so `Text(...)`, `Button(...)`, resources, slot bindings, and other components require neither a public `MinecraftUiContext` nor an extra root builder.

```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.screen.ScreenDefinition

fun confirmationScreen(onConfirm: () -> Unit): ScreenDefinition =
    ScreenDefinition("Confirm action") {
        Column(
            modifier = Modifier.Empty.padding(12),
            spacing = 8,
        ) {
            Text("Continue with this action?")
            Button(
                "Yes",
                modifier = Modifier.Empty.onPress(onConfirm),
            )
        }
    }
```

The `integration/api` main source set compiles a representative screen with only `:api` and checks that no runtime project enters its compile classpath.
Its tests then supply runtime implementations to exercise rendering, input, synchronized bindings, and lifecycle behavior.

<!-- strata-component-showcase:start -->
<!-- Generated file. Do not edit. -->

## Minecraft component showcase

This deterministic image is the actual 320 by 180 `ConfirmScreen` reconstruction from the frame that passed exact native-screen, Fabric-adapter, and headless comparison.

![Strata component showcase](docs/components/overview.png)

### Overview source

```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the deterministic Minecraft ConfirmScreen content used by the Fabric and headless parity paths.
 *
 * @return one-shot screen definition reproducing the native title, message, and button-row geometry.
 */
internal fun createConfirmScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Strata parity") {
        Stack(
            modifier = Modifier.Empty.size(320, 180).menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                spacing = 24,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Column(
                    spacing = 8,
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    Text("Confirm action")
                    Text("Continue with this action?")
                }
                Row(spacing = 4) {
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

- `api` is the only compile-time dependency required for application authoring.
  It contains screen definitions, all standard component entry points and configuration types, layout, resources, bindings, modifiers, and the public custom `Element`/`Node` SPI.
- `runtime/core` is configured as a publishable retained engine that measures, lays out, paints, dispatches input, and flattens unresolved semantics on the JVM. It has not been published to an external repository.
- `runtime/headless` is configured as a publishable headless adapter over the retained core.
  Its verified synchronous entry points render a positive fixed viewport into immutable scaled ARGB pixels, deterministic metadata-free RGBA8 PNG bytes, and logical unscaled semantics.
- `runtime/minecraft` is the publishable Minecraft-independent implementation of API components, profile/resource resolution, declarative live-slot and player-skin bindings, and the owner-thread host over the retained core.
  It applies every frame to exact fixed logical viewport constraints without exposing Fabric, resource-manager objects, or mapped game types.
- `runtime/minecraft-fabric-26.2` is the client-only Java 25 version boundary for the latest Java release's resource, screen, rendering, and input adapter.
  It nests the common runtime jars in the mod artifact, keeps Minecraft types out of the common modules, and passes an exact loaded-game native/Fabric/headless pixel comparison.
- `runtime/minecraft-fabric-26.1` is the client-only Java 25 boundary for Minecraft 26.1.
  It shares the verified unobfuscated adapter contract with 26.2 and isolates the release's `Minecraft.screen` access behind its version boundary.
- `runtime/minecraft-fabric-1.21.11` is the client-only Java 21 boundary for Minecraft 1.21.11.
  It compiles against official Mojang mappings, remaps its published Fabric jar, and combines the shared legacy adapter with the `Identifier` resource-name alias root.
- `runtime/minecraft-fabric-1.21.10` is the client-only Java 21 boundary for Minecraft 1.21.10.
  It compiles the same legacy adapter against official Mojang mappings, remaps its published Fabric jar, and owns only the older `ResourceLocation` aliases and release metadata locally.
- `runtime/minecraft-fabric-1.21.9` is the client-only Java 21 boundary for Minecraft 1.21.9.
  It compiles the same complete legacy adapter against official Mojang mappings, remaps its published Fabric jar, and owns its `ResourceLocation` aliases and release metadata locally.
- `runtime/minecraft-fabric-1.21.8` is the client-only Java 21 boundary for Minecraft 1.21.8.
  It reuses the native-neutral legacy host and inventory behavior while isolating primitive Screen callbacks, primitive key binding matching, and the older resource-location player-skin API.
- `runtime/minecraft-fabric-1.21.7` is the client-only Java 21 boundary for Minecraft 1.21.7.
  It compiles the complete primitive-input release family against the exact 1.21.7 client, remaps the distribution jar, and owns only release metadata and `ResourceLocation` aliases locally.
- `runtime/minecraft-fabric-1.21.6` is the client-only Java 21 boundary for Minecraft 1.21.6.
  It compiles the same complete primitive-input release family against the exact 1.21.6 client, remaps the distribution jar, and owns only release metadata and `ResourceLocation` aliases locally.
- `runtime/minecraft-fabric-1.21.5` is the client-only Java 21 boundary for Minecraft 1.21.5.
  It keeps the complete primitive-input and resource-location-skin behavior while isolating Minecraft's render-type texture submission and pose-stack carried-item depth from the pipeline and stratum APIs used by later releases.
- `runtime/minecraft-fabric-1.21.4` is the client-only Java 21 boundary for Minecraft 1.21.4.
  It compiles the same render-type, pose-stack, primitive-input, and resource-location-skin behavior while isolating the unnamed `DynamicTexture` constructor used before Minecraft 1.21.5.
- `runtime/minecraft-fabric-1.21.3` is the client-only Java 21 boundary for Minecraft 1.21.3.
  It retains the unnamed dynamic-texture, render-type, pose-stack, and primitive-input behavior while isolating `SkinManager.getOrLoad` returning a direct player skin rather than an optional value.
- `runtime/minecraft-fabric-1.21.2` is the client-only Java 21 boundary for Minecraft 1.21.2.
  Its compiler and loaded clients prove the complete 1.21.3 direct-skin and standalone-runner source families remain valid against the exact older game and Fabric API fixture.
- `runtime/minecraft-fabric-1.21.1` is the client-only Java 21 boundary for Minecraft 1.21.1.
  It keeps the shared direct-skin and standalone-runner behavior while isolating the older native-image channel order and texture blit signature; its extracted profile reproduces the code-defined Slot and Tooltip treatments and uses the active resource pack's native white boss-bar sprites for generic progress.
- `runtime/minecraft-fabric-1.21` is the client-only Java 21 boundary for Minecraft 1.21.
  Its exact compiler and loaded clients prove the same direct-skin, standalone-runner, ABGR native-image, pre-render-type blit, fixed-nine-slice, Slot, Tooltip, and horizontal-progress boundaries remain valid against the original Tricky Trials release and its dedicated Fabric API fixture.
- `runtime/minecraft-fabric-1.20.6` is the client-only Java 21 boundary for Minecraft 1.20.6.
  It reuses the compiler-proven legacy behavior while isolating the older `ResourceLocation` constructors and parser, then verifies the complete suite from development classes and the remapped production jars.
- `runtime/minecraft-fabric-1.20.5` is the client-only Java 21 boundary for Minecraft 1.20.5.
  Its exact compiler and loaded clients prove the complete 1.20.6 capability boundary against the preceding game release and its dedicated final Fabric API fixture.
- `runtime/minecraft-fabric-1.20.4` is the client-only Java 17 boundary for Minecraft 1.20.4.
  It selects the active resource pack's legacy menu, list, and separator assets, reproduces the code-defined black scrollbar track, and isolates the pre-`ResolvableProfile` skin lookup, primitive key binding, and standalone world-cleanup APIs behind exact compile-time bridges.
- `integration/api` proves API-only application compilation, then exercises a third-party primitive and the common Minecraft host from its test classpath.
- `integration:minecraft-fabric-1.21.11` through `integration:minecraft-fabric-1.20.4` compile the shared legacy loaded-client suite against their exact game and Fabric API dependencies, then run it from both development classes and remapped production jars; none of these modules is published.
  Releases from 1.21.4 use Fabric Client GameTest, while 1.21.3 through 1.20.4 run the same assertions from a normal client entrypoint because their official Fabric APIs predate that test module.
- `integration/minecraft-fabric-26.1` runs the shared loaded client and integrated-server suite against actual 26.1 dependencies; it is not published.
- `integration/minecraft-fabric-26.2` runs loaded client parity scenes against actual 26.2 resources, vanilla screens, the selected player skin, server-synchronized player/custom/ender-chest Slots, and resource-pack-aware industrial and progression screens; it is not published.
- `integration/docs` extracts the compiled component and complete-screen sources and synchronizes only images carrying the matching GameTest receipt into one generated document; it is not published.

See [Architecture](docs/architecture.md) for dependency boundaries and extension rules, and [Element SPI](docs/element-spi.md) for the smallest complete custom primitive.

## Documentation

- [Architecture](docs/architecture.md) explains the public SPI, runtime boundaries, and testing strategy.
- [Built-in layout components](docs/layout.md) specifies Row, Column, Stack, Grid, and Spacer measurement, arrangement, alignment, and weight behavior.
- [Component showcase](docs/components.md) contains every generated example and Minecraft-backed image from the exact native/Fabric/headless parity frame in one document.
- [Element SPI](docs/element-spi.md) explains node ownership, lifecycle, retained phases, and extension points.
- [Modifiers](docs/modifiers.md) explains active modifier nodes, typed parent data, positional reconciliation, lifecycle, and extension failures.
- [External state sources](docs/state-sources.md) specifies linearizable revisioned state observation across threads.
- [UI sessions](docs/ui-sessions.md) specifies retained state, frame cutoffs, coroutine generations, and failure handling inside the core runtime.
- [Rendering performance](docs/performance.md) records the JMH methodology, allocation evidence, cache and retention gates, and the completed Minecraft 1.21 family baseline.
- [Build and release](docs/build.md) lists local quality checks, the aggregated Dokka GitHub Pages site, and publication requirements.
- [Supporting a new Minecraft version](docs/minecraft-versions.md) defines the evidence, implementation, and compatibility process for another adapter.

## Build

Use the checked-in Gradle wrapper:

```shell
./gradlew check koverHtmlReport koverXmlReport -Pkover
```

## License

Strata is available under the [MIT License](LICENSE).
