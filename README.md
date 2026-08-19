<div align="center">
  <img src="icon.svg" alt="Strata" width="112">
  <h1>Strata</h1>
</div>

A declarative UI framework for Minecraft with reusable component trees, version-independent layout and state, and headless testing.

Strata is pronounced “STRAY-tuh” (`/ˈstreɪtə/`) and is the plural of *stratum*, meaning a layer.
The name reflects its layered design: declarative components, retained UI behavior, portable rendering, and environment-specific adapters.

Strata is under development toward `0.1.0`; public artifacts are not available yet.
Features are documented as available only after executable tests and generated examples verify them.

## Why Strata exists

Minecraft screens often combine layout, input handling, state changes, text resolution, game assets, and version-specific calls in one class.
That makes the result difficult to reuse and difficult to verify outside a running client.

The release design separates those concerns into layers:

- application code declares components and state;
- layout components measure and place their children from constraints instead of visual-tuning coordinates;
- retained nodes perform incremental measurement, layout, painting, input, semantics, and lifecycle work;
- the headless runtime renders and inspects the same component tree on the JVM;
- a versioned Minecraft runtime resolves native text, assets, drawing, input, and screen lifecycle behavior.

The public element, node, drawing, and modifier contracts are designed for extension.
A custom primitive must work through those contracts without registering its concrete class in a central component dispatcher.

## Release module layout

Modules enter the build only with working behavior and tests.
The `0.1.0` dependency boundaries are:

- `api` is the platform-neutral component and runtime contract used by application code.
- `runtime/headless` measures, renders, and inspects component trees without Minecraft.
- `runtime/minecraft` defines Minecraft-hosting contracts without importing game or Fabric classes.
- `runtime/minecraft-fabric-{version}` provides the installable adapter for one Minecraft and Fabric version.
- `integration` contains reusable and version-specific verification that needs a real game environment.

See [Architecture](docs/architecture.md) for dependency boundaries and extension rules.

## Documentation

- [Architecture](docs/architecture.md) explains the public SPI, runtime boundaries, and testing strategy.
- [Build and release](docs/build.md) lists local quality checks and publication requirements.
- [Supporting a new Minecraft version](docs/minecraft-versions.md) defines the evidence, implementation, and compatibility process for another adapter.

## Build

Use the checked-in Gradle wrapper:

```shell
./gradlew check koverHtmlReport koverXmlReport
```

## License

Strata is available under the [MIT License](LICENSE).
