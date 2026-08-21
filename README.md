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
- active modifiers provide checked padding, size constraints, background painting, unresolved semantics, and typed layout parent data without changing component implementations;
- the retained core runtime emits draw commands and unresolved semantics on the JVM;
- the common Minecraft runtime owns one-shot screen definitions, immutable asset profiles, callback-scoped menu/text/pointer-button components, and a synchronous fixed-viewport host over the retained core;
- a future versioned Minecraft runtime will resolve native text, assets, drawing, input, and game screen lifecycle behavior.

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

## Headless component showcase

This deterministic JVM-headless output is not a Minecraft screenshot or capture.

![Strata component showcase](docs/components/images/overview.png)

### Overview source

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.headless.HeadlessFrame
import dev.s7a.strata.runtime.headless.renderHeadless

/**
 * Builds the overview tree shared by its metadata and renderer.
 *
 * @return the public element tree before rendering.
 */
internal fun overviewDescription(): Element =
    buildUi {
        Column(
            modifier =
                Modifier.Empty
                    .fillMaxSize()
                    .background(ArgbColor(0xFF111827.toInt()))
                    .padding(4),
            spacing = 4,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = HorizontalAlignment.Center,
        ) {
            Row(
                modifier = Modifier.Empty.size(60, 12),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = VerticalAlignment.Center,
            ) {
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(10, 8)
                            .background(ArgbColor(0xFF22D3EE.toInt())),
                )
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(10, 10)
                            .background(ArgbColor(0xFFA78BFA.toInt())),
                )
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(10, 6)
                            .background(ArgbColor(0xFFFBBF24.toInt())),
                )
            }
            Box(
                modifier =
                    Modifier.Empty
                        .size(44, 16)
                        .background(ArgbColor(0xFF1F2937.toInt())),
                contentAlignment = Alignment.Center,
            ) {
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(24, 8)
                            .background(ArgbColor(0xFFFB7185.toInt()))
                            .align(Alignment.Center),
                )
            }
        }
    }

/**
 * Renders the overview scene into a deterministic headless frame.
 *
 * @return the rendered overview frame.
 */
internal fun overview(): HeadlessFrame = renderHeadless(overviewDescription(), IntSize(72, 44), scale = 3)
```

[Open the component showcase index](docs/components/README.md)
<!-- strata-component-showcase:end -->

## Module layout

Modules enter the build only with working behavior and tests.
The dependency boundaries are:

- `api` is the platform-neutral public contract used by application and primitive code.
- `runtime/core` is configured as a publishable retained engine that measures, lays out, paints, dispatches input, and flattens unresolved semantics on the JVM. It has not been published to an external repository.
- `runtime/headless` is configured as a publishable headless adapter over the retained core.
  Its verified synchronous entry points render a positive fixed viewport into immutable scaled ARGB pixels, deterministic metadata-free RGBA8 PNG bytes, and logical unscaled semantics.
- `runtime/minecraft` is configured as a publishable Minecraft-independent one-shot screen definition, immutable profile, callback-scoped menu/text/pointer-button component set, and owner-thread host over the retained core.
  It applies every frame to exact fixed logical viewport constraints without exposing Fabric, resources, or mapped game types.
- `integration/api` compiles and exercises a third-party primitive against the public contracts, retained core, and common Minecraft host.
- `integration/docs` contains compiled JVM-headless component showcase scenarios and freshness tasks; it is not published.

See [Architecture](docs/architecture.md) for dependency boundaries and extension rules, and [Element SPI](docs/element-spi.md) for the smallest complete custom primitive.

## Documentation

- [Architecture](docs/architecture.md) explains the public SPI, runtime boundaries, and testing strategy.
- [Built-in layout components](docs/layout.md) specifies Row, Column, Box, and Spacer measurement, arrangement, alignment, and weight behavior.
- [Component showcase](docs/components/README.md) contains generated JVM-headless examples when the showcase files are synchronized.
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
