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
- the retained core runtime emits draw commands and unresolved semantics on the JVM;
- a versioned Minecraft runtime resolves native text, assets, drawing, input, and screen lifecycle behavior.

The public element, node, and drawing contracts are designed for extension.
A custom primitive must work through those contracts without registering its concrete class in a central component dispatcher.

## Module layout

Modules enter the build only with working behavior and tests.
The dependency boundaries are:

- `api` is the platform-neutral public contract used by application and primitive code.
- `runtime/core` is configured as a publishable retained engine that measures, lays out, paints, dispatches input, and flattens unresolved semantics on the JVM. It has not been published to an external repository.
- `integration/api` compiles and exercises a third-party primitive against the public contracts and runtime core.

See [Architecture](docs/architecture.md) for dependency boundaries and extension rules, and [Element SPI](docs/element-spi.md) for the smallest complete custom primitive.

## Documentation

- [Architecture](docs/architecture.md) explains the public SPI, runtime boundaries, and testing strategy.
- [Element SPI](docs/element-spi.md) explains node ownership, lifecycle, retained phases, and extension points.
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
