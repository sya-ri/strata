<div align="center">
  <img src="icon.svg" alt="Strata" width="112">
  <h1>Strata</h1>
</div>

Declarative Minecraft UI with reusable component trees, version-independent layout and state, and headless testing without launching Minecraft.

Strata is pronounced “STRAY-tuh” (`/ˈstreɪtə/`) and is the plural of *stratum*, meaning a layer.
The name reflects its layered design: declarative components, retained UI behavior, portable rendering, and environment-specific adapters.

Strata is distributed through Maven Central for development, as a separate client Fabric Mod through Modrinth, and as a public Codex skill in this repository.

## Why Strata exists

Minecraft screens often combine layout, input handling, state changes, text resolution, game assets, and version-specific calls in one class.
That makes the result difficult to reuse and difficult to verify outside a running client.

The design separates those concerns into layers:

- application code declares components and owns application state;
- layout components measure and place their children from constraints instead of visual-tuning coordinates;
- retained nodes perform incremental measurement, layout, painting, input, semantics, and lifecycle work;
- active modifiers provide checked padding, size constraints, background painting, unresolved semantics, typed pointer/keyboard/text/focus actions, shared pointer-and-keyboard activation, and typed layout parent data without changing component implementations;
- the retained core runtime emits draw commands and unresolved semantics on the JVM;
- the platform-neutral API owns one-shot screen definitions; Row/FlowRow/Column/Stack/Grid layout; Text, TextField, TextArea, Button, Checkbox, CycleButton, Slider, Tab, ScrollArea, Scrollbar, VirtualList, SelectionList, Image, Canvas, TiledImage, Slot, PlayerHead, LoadingIndicator, and ProgressBar authoring; resource identifiers; slot locators; skin sources; and active modifiers, so application source compiles without a runtime dependency;
- the common Minecraft runtime installs itself behind that API, resolves the selected profile and resources, synchronizes bound slots with the active server menu, and hosts the retained tree without exposing a context receiver to application code;
- versioned Fabric boundaries extract the supported native profile, resolve Mod images and current-player skin pixels through active resource and texture paths, and adapt common frames, typed mouse/keyboard/text input, and screen lifecycle on the client thread; loaded client GameTests provide native/Fabric/headless parity and server-authoritative interaction evidence at the applicable version boundaries.

The public element, node, and drawing contracts are designed for extension.
A custom primitive must work through those contracts without registering its concrete class in a central component dispatcher.
Applications may also define purpose-specific components as ordinary compositions of public primitives.
Strata's own standard built-ins are limited to focused components with multiple natural uses, but that generality review does not restrict application or Mod components such as an energy gauge or social-entry row.
`Canvas` embeds CPU image frames, leased native textures, or a custom offscreen renderer in an explicitly sized, input-passive rectangle.
Its sources own no input hierarchy: compose `onCapturedPointerEvent`, `onKeyEvent`, and focus modifiers when interaction is needed.
CPU sources render directly in Headless; native capture requires an immutable snapshot from that exact presentation and never performs an implicit GPU readback.
See [Canvas ownership and rendering](docs/architecture.md#canvas-ownership-and-rendering) for source, capture, and lifetime contracts.

`TiledImage` presents maps, scans, and schematics from independently revisioned immutable tiles without rebuilding one viewport-sized image when the player moves.
Caller-owned `PanZoomState` and `panZoom` handle bounded navigation, while fixed-size overlay children follow content coordinates through `TiledImageScope.atContentPosition`.
See [Tiled images and pan/zoom](docs/tiled-images.md) for source generations, LOD fallback, cache invalidation, and overlay ownership.

<!-- strata-component-showcase:start -->
<!-- Generated file. Do not edit. -->

## Minecraft component showcase

This deterministic image is a fresh 320 by 180 headless `ConfirmScreen` reconstruction using explicit Minecraft asset files.
Generation does not start Minecraft or create a GPU context; native-screen, Fabric-adapter, and headless comparisons run in a separate [acceptance gate](docs/components.md).

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
import dev.s7a.strata.modifier.onActivate
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
                        modifier = Modifier.Empty.onActivate {},
                    )
                    Button(
                        "No",
                        modifier = Modifier.Empty.onActivate {},
                    )
                }
            }
        }
    }
```

[Open the complete component showcase](docs/components.md)
<!-- strata-component-showcase:end -->

## Installation and API-only authoring

Application UI source needs only `strata-api` on its compile classpath.
Install exactly one version-matched runtime as a separate client Fabric Mod together with Fabric Language Kotlin; do not bundle multiple versioned Strata runtimes.
Replace `<strata-version>` with one released Strata version and keep every Strata coordinate and dependency requirement aligned to it.

```kotlin
dependencies {
    compileOnly("dev.s7a.strata:strata-api:<strata-version>")
    modRuntimeOnly("dev.s7a.strata:strata-runtime-minecraft-fabric-<minecraft-version>:<strata-version>")
    modRuntimeOnly("net.fabricmc:fabric-language-kotlin:<compatible-version>")
}
```

The version-matched runtimes are also available from [Modrinth](https://modrinth.com/mod/strata-ui).
Declare it as a required dependency in the consuming Mod so `ScreenDefinition.open()` always has a presenter in production:

```json
{
  "depends": {
    "strata": ">=<strata-version>"
  }
}
```

The selected host adapter is a runtime concern.
The supported Minecraft range begins at 1.20; Minecraft 1.19 and older releases are outside the project scope.

See [Supported Fabric runtimes](docs/architecture.md#supported-fabric-runtimes) for artifact names, Java requirements, and verification details.

`ScreenDefinition` evaluates its callback after the Minecraft runtime has installed the active profile, so `Text(...)`, `Button(...)`, resources, slot bindings, and other components require neither a public `MinecraftUiContext` nor an extra root builder.
`Button` and `Tab` own appearance and semantics without implicit focus or activation; compose `onActivate(enabled) { ... }` when primary pointer presses and focused Enter or Space presses mean the same action, and keep `onPress` for pointer-specific behavior.

<a id="api-only-open-example"></a>

<!-- strata-api-open-example:start -->
```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds and opens a screen while compiling against `strata-api` alone.
 *
 * The separately installed Fabric runtime supplies Minecraft rendering and becomes the current screen.
 */
internal fun openConfirmationScreen(onConfirm: () -> Unit) {
    ScreenDefinition("Confirm action") {
        Column(
            modifier =
                Modifier.Empty
                    .size(320, 180)
                    .menuBackground()
                    .padding(12),
            spacing = 8,
            horizontalAlignment = HorizontalAlignment.Center,
        ) {
            Text("Continue with this action?")
            Button(
                "Yes",
                modifier = Modifier.Empty.onActivate(onConfirm),
            )
        }
    }.open()
}
```
<!-- strata-api-open-example:end -->

The `integration/api` main source set compiles a representative screen with only `:api` and checks that no runtime project enters its compile classpath.
Its tests then supply runtime implementations to exercise rendering, input, synchronized bindings, and lifecycle behavior.

## Public Codex skill

The checked-in `skills/strata` package teaches an AI consumer the exact component, Modifier, parent-scope, state, and binding signatures, plus Strata-specific layout and custom-component review guidance.

Preview it with GitHub CLI:

```shell
gh skill preview sya-ri/strata skills/strata
```

Install it with the Skills CLI:

```shell
npx skills add sya-ri/strata --skill strata
```

## Documentation

- [Architecture](docs/architecture.md) explains the public SPI, runtime boundaries, and testing strategy.
- [Built-in layout components](docs/layout.md) specifies Row, FlowRow, Column, Stack, Grid, and Spacer measurement, wrapping, arrangement, alignment, and weight behavior.
- [Tiled images and pan/zoom](docs/tiled-images.md) specifies bounded tile subscriptions, source generations, LOD fallback, navigation, and content-coordinate overlays.
- [Component showcase](docs/components.md) contains the compiled examples and verified Minecraft-backed images in one document.
- [Text and text input](docs/text.md) explains Unicode values, resource-pack font selection, scalar editing, and delivered IME composition.
- [Font resources](docs/font-resources.md) covers offline font snapshots, native backend dependencies, and ownership.
- [Element SPI](docs/element-spi.md) explains node ownership, lifecycle, retained phases, and extension points.
- [Modifiers](docs/modifiers.md) explains active modifier nodes, typed parent data, positional reconciliation, lifecycle, and extension failures.
- [External state sources](docs/state-sources.md) specifies linearizable revisioned state observation across threads.
- [UI sessions](docs/ui-sessions.md) specifies retained state, frame cutoffs, coroutine generations, and failure handling inside the core runtime.
- [Rendering performance](docs/performance.md) records the JMH methodology, allocation evidence, and cache, parity, lifecycle, and retention gates.
- [Contributing](CONTRIBUTING.md) covers development setup, verification, and contribution guidelines.
- [Build and release](docs/build.md) lists local quality checks, the aggregated Dokka GitHub Pages site, and publication requirements.
- [Supporting a new Minecraft version](docs/minecraft-versions.md) defines the evidence, implementation, and compatibility process for another adapter.
- [Changelog](CHANGELOG.md) records versioned changes and compatibility notes; detailed release notes are kept under `docs/releases`.

The [Dokka API reference](https://gh.s7a.dev/strata/) is published through GitHub Pages.
The reader guides linked above are Markdown documents rendered by GitHub in this repository.

### Module layout

| Module | Role |
| --- | --- |
| `api` | The only compile-time dependency needed to author UI and custom components. |
| `runtime/core` | Shared retained engine for layout, rendering, input, and screen sessions. |
| `runtime/headless` | Deterministic rendering and UI tests without launching Minecraft. |
| `runtime/minecraft` | Common component implementations, resources, bindings, and screen hosting. |
| `runtime/minecraft-fonts-lwjgl` | Optional CPU font backend for resource fonts; uses target-matched native libraries. |
| `runtime/minecraft-fabric-<version>` | Client Fabric adapter; install exactly one matching your Minecraft version. |
| `integration/*` | API, loaded-client, and documentation verification; not published. |

See [Architecture](docs/architecture.md#module-boundaries) for dependency boundaries and version-specific implementation details.

## Known limitations

- Resource-font native acceptance covers the tested fixtures and device observations; [font acceptance scope](docs/font-resources.md#acceptance-evidence) describes exact comparisons, verified GPU differences, and remaining numeric limits.
- Unicode and custom-font coverage depend on the selected resource pack; the older finite ASCII profile format remains a separate compatibility mode.
- Font rendering targets standard Minecraft providers and shaders, without OS fonts, an additional color-emoji engine, or new translation-key resolution.
- `TextField` edits one line and `TextArea` edits canonical LF multiline values; both use Unicode scalar boundaries, not grapheme clusters, and IME support is limited to input events supplied by the game.
- Resource-backed images use fixed declared logical dimensions and deterministic nearest sampling; layout does not infer dimensions from a replacement image.
- Canvas displays ordinary RGBA8 straight-alpha color with nearest sampling; HDR, multisample and depth images, decoders, audio, browser engines, and direct rendering into the current GUI framebuffer are outside its scope.

## License

Strata is available under the [MIT License](LICENSE).
