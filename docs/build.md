# Build and release

The checked-in wrapper and version catalog are the source of truth for build-tool and dependency versions.
Common modules target the baseline Java toolchain; version-specific Minecraft modules target the toolchain required by their game version.
The `runtime:minecraft-fabric-26.2` and `runtime:minecraft-fabric-26.1` modules target Java 25, use the version-catalog Fabric Loom plugin in no-remap mode for their unobfuscated clients, and package the `api`, `runtime:core`, `runtime:headless`, and `runtime:minecraft` jars under `META-INF/jars` exactly once.
Their Fabric metadata declares fixed Loader, Minecraft, and Fabric Language Kotlin runtime requirements; neither runtime uses Fabric API.
The `runtime:minecraft-fabric-1.21.11` module targets Java 21, compiles against official Mojang mappings, remaps its distribution jar through the version-catalog Fabric Loom remap plugin, and packages the same common jars exactly once.
All three Fabric runtime modules declare fixed Loader, Minecraft, Fabric Language Kotlin, and Java runtime requirements; none uses Fabric API at runtime.
The 26.x projects compile the complete neutral `runtime/minecraft-fabric-unobfuscated` source tree and add only their version-specific current-screen bridge and metadata.
The 1.21.11 project links only an enumerated set of neutral files proven compatible and owns its older screen, focused-input, and inventory bridges locally.
Consumers select exactly one versioned runtime artifact because the adapters deliberately expose the same packages and public class names.

Every Kotlin compilation uses explicit API mode and treats warnings as errors.
Detekt and Kotlinter are applied to all project modules.
Kover exposes HTML and XML reports without enforcing a coverage threshold.
Run aggregate coverage with `./gradlew koverHtmlReport koverXmlReport -Pkover`; each report task first runs the ordinary JVM test suite selected by `koverJvmTests`.

Each publishable JVM module has a Maven publication with source and Javadoc artifacts, MIT license metadata, SCM metadata, and an optional in-memory signing setup.
Provide `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, optional `signingInMemoryKeyId`, and optional `signingInMemoryKeyPassword` Gradle properties when publishing to Maven Central.
Environment variables use the `ORG_GRADLE_PROJECT_` prefix followed by the same property name.

The root `dokkaGenerate` task aggregates every published module into `build/dokka/html`.
The Documentation workflow builds that exact directory on pushes to `master` and deploys it through GitHub Pages' artifact and OIDC deployment path.
Repository settings must select GitHub Actions as the Pages source; the workflow requires only read access to contents plus `pages: write` and `id-token: write`.

Qodana runs its recommended JVM inspection profile in CI without a baseline.
Static-analysis rules are enabled when they produce actionable improvements; rules that systematically make code less clear are disabled with a durable rationale in the checked-in configuration.

The nonpublished `integration:minecraft-fabric-26.2` and `integration:minecraft-fabric-26.1` modules compile the same neutral loaded-client suite against their exact game and Fabric API dependencies.
`./gradlew :integration:minecraft-fabric-26.2:runClientGameTest` fixes the viewport, GUI scale, locale, resource profile, and pointer state, then requires exact native-Screen, Fabric-adapter, and headless ARGB equality before writing build-only evidence.
`./gradlew :integration:minecraft-fabric-26.1:runClientGameTest` applies the same acceptance conditions to 26.1 and writes a version-qualified build receipt.
The nonpublished `integration:minecraft-fabric-1.21.11` module compiles an independent Java 21 loaded-client suite against the remapped adapter and its exact Fabric API dependency.
`./gradlew :integration:minecraft-fabric-1.21.11:runClientGameTest` writes version-qualified build evidence after exercising the older adapter inside the actual client.
`./gradlew :integration:minecraft-fabric-1.21.11:runProductionClientGameTest` packages and remaps both the integration test Mod and runtime Mod, then repeats the loaded suite from those production jars with their nested common runtime jars.

The nonpublished `integration:docs` module owns two source-safe showcase tasks.
`./gradlew :integration:docs:checkComponentShowcase` depends on that loaded GameTest, verifies its receipt and image hashes, stages the compiled Minecraft-component scenario sources and verified crops, and checks freshness without modifying repository files.
`./gradlew :integration:docs:generateComponentShowcase` performs the same parity preflight and synchronizes the combined `docs/components.md` showcase, parity receipt, PNG files, and anchored root README region.
Generated output is owned by the showcase generator; manual edits are reported as stale by the checker.

Run `./gradlew :quality:benchmarks:jmh` for the temporary JSON report and follow the methodology and acceptance gates in [Rendering performance](performance.md).
