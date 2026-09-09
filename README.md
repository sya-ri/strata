<div align="center">
  <img src="icon.svg" alt="Strata" width="112">
  <h1>Strata</h1>
</div>

Declarative Minecraft UI with reusable components, caller-owned state, and headless rendering.

Minecraft screens often mix layout, input, state, resources, and version-specific calls in one class.
Strata separates those responsibilities so an interface can be composed, reused, and tested through a common API.
Applications describe a component tree; the runtime retains its nodes and adapts rendering, input, and lifecycle to the selected environment.

## What you can build

- Arrange interfaces with rows, columns, wrapping groups, grids, and overlays.
- Compose controls, text editors, scrolling views, images, and Minecraft resource-backed content.
- Add sizing, backgrounds, pointer and keyboard actions, focus, and layout parent data through modifiers.
- Reuse application components or implement custom primitives through the public Element and Node SPI.
- Render portable drawing and semantics in JVM tests, with separate loaded-client verification for native behavior.

The [component overview](docs/reference/components.md) shows the available components with images and compiled examples.
[Complete screens](docs/examples/screens.md) demonstrate how they fit together.

<!-- strata-component-showcase:start -->
<!-- Generated file. Do not edit. -->

## A screen built from components

A confirmation screen combines text, buttons, and layout components into a reusable UI definition.

![Strata component showcase](docs/components/overview.png)

[Compare components and browse their examples](docs/reference/components.md).
<!-- strata-component-showcase:end -->

## Installation

<!-- strata-installation:start -->
Application UI source needs only `strata-api` on its compile classpath.
Install exactly one version-matched runtime as a separate client Fabric Mod together with Fabric Language Kotlin; do not bundle multiple versioned Strata runtimes.

```kotlin
dependencies {
    compileOnly("dev.s7a.strata:strata-api:0.1.5")
    modRuntimeOnly("dev.s7a.strata:strata-runtime-minecraft-fabric-<minecraft-version>:0.1.5")
    modRuntimeOnly("net.fabricmc:fabric-language-kotlin:<compatible-version>")
}
```

The version-matched runtimes are also available from [Modrinth](https://modrinth.com/mod/strata-ui).
Declare it as a required dependency in the consuming Mod so `ScreenDefinition.open()` always has a presenter in production:

```json
{
  "depends": {
    "strata": ">=0.1.5"
  }
}
```
<!-- strata-installation:end -->

Choose exactly one runtime matching the target Minecraft release.
The [compatibility reference](docs/reference/compatibility.md) lists supported targets, artifact names, and Java requirements.

## Open a screen

Application UI source compiles against `strata-api` alone.
Create a one-shot `ScreenDefinition` and open it on the installed runtime's owner thread.
The runtime supplies the active resource profile before evaluating the component callback.
Actions are composed with modifiers, so controls can share pointer and keyboard behavior.

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

The example is compiled from the shipped API-only integration source.
[Screens and state](docs/guides/screens-and-state.md) explains definition ownership, state, input, and resource use.

## Choose modules

| Module | Use |
| --- | --- |
| `api` | Compile application UI and custom components. |
| `runtime/core` | Integrate the shared retained engine through its runtime contracts. |
| `runtime/headless` | Render portable output and inspect UI behavior without launching Minecraft. |
| `runtime/minecraft` | Host profile-backed components and resources in a common runtime. |
| `runtime/minecraft-fonts-lwjgl` | Supply a CPU backend for offline resource-font rendering. |
| `runtime/minecraft-fabric-<version>` | Run the interface as a client Fabric screen on one matching game version. |

Versioned Fabric Mods package their common runtime libraries.
Integration modules contain verification and examples and are not published.
See [architecture](docs/development/architecture.md) for dependency boundaries.

## Documentation

Start with the [documentation index](docs/README.md), then use [layout](docs/guides/layout.md), [modifiers](docs/guides/modifiers.md), and [text and editing](docs/guides/text.md) to build an interface.
The [Dokka API reference](https://gh.s7a.dev/strata/) contains signatures and KDoc; the [Element SPI](docs/reference/element-spi.md) explains custom primitives.
[Contributing](CONTRIBUTING.md) covers development, and the [changelog](CHANGELOG.md) links release changes and migration notes.

The public [Strata skill](skills/strata/SKILL.md) provides checked authoring guidance for AI tools.
Preview it with `gh skill preview sya-ri/strata skills/strata` or install it with `npx skills add sya-ri/strata --skill strata`.
Runtime Mods are also distributed through [Modrinth](https://modrinth.com/mod/strata-ui).

Strata is pronounced “STRAY-tuh” (`/ˈstreɪtə/`), the plural of *stratum*, meaning a layer.
It is available under the [MIT License](LICENSE).
