# Build and release

The checked-in wrapper and version catalog are the source of truth for build-tool and dependency versions.
Common modules target the baseline Java toolchain; version-specific Minecraft modules target the toolchain required by their game version.
The `runtime:minecraft-fabric-26.2` and `runtime:minecraft-fabric-26.1` modules target Java 25, use the version-catalog Fabric Loom plugin in no-remap mode for their unobfuscated clients, and package the `api`, `runtime:core`, `runtime:headless`, and `runtime:minecraft` jars under `META-INF/jars` exactly once.
Their Fabric metadata declares an exact Minecraft requirement and catalog-derived Loader and Fabric Language Kotlin lower bounds; neither runtime uses Fabric API.
The `runtime:minecraft-fabric-1.21.11` through `runtime:minecraft-fabric-1.20.6` modules target Java 21, compile against official Mojang mappings, remap their distribution jars through the version-catalog Fabric Loom remap plugin, and package the same common jars exactly once.
All fifteen Fabric runtime modules declare an exact Minecraft requirement and catalog-derived Loader, Fabric Language Kotlin, and Java lower bounds; none uses Fabric API at runtime.
The 26.x projects compile the complete neutral `runtime/minecraft-fabric-shared`, `runtime/minecraft-fabric-identifier`, and `runtime/minecraft-fabric-unobfuscated` source trees and add only their version-specific current-screen bridge and metadata.
The 1.21.11 project combines the cross-version and identifier-alias roots with the remapped 1.21 adapter sources and the record-input release-family root.
The 1.21.10 and 1.21.9 projects combine the same cross-version, remapped 1.21, and record-input roots but keep their `ResourceLocation` aliases in each versioned module because that mapped name is not shared by every consumer.
The 1.21.8 through 1.21.4 projects combine the cross-version and remapped 1.21 roots with the complete primitive-input release-family root, while 1.21.3 through 1.21 reuse its compatible Kotlin screen root and the complete Java bridge root for the direct player-skin result.
The 1.21.5 through 1.21.2 projects add the render-type and pose-stack bridge root, 1.21.1 and 1.21 supply their older direct texture blitter locally, and 1.21.6 through 1.21.11 add the render-pipeline and stratum bridge root.
The 1.20.6 project reuses the compiler-proven 1.21.1 capability roots but owns the older constructor-based `ResourceLocation` factory and parser together with its exact native texture bridges.
The shared frame presenter calls compile-time dynamic-texture and native-pixel bridges supplied by the 1.21 through 1.21.5 version projects, the 1.21.6 release-family root, or the unobfuscated release-family root, preserving ABGR-versus-ARGB access and unnamed-versus-named texture construction without reflection.
Code enters the legacy source root only after every consuming target compiles it and passes both development and production-jar loaded-client verification.
Every source directory is linked as a whole root because Gradle file-tree include filters are not a reliable IDE or static-analysis ownership boundary.
Consumers select exactly one versioned runtime artifact because the adapters deliberately expose the same packages and public class names.
The root build owns one typed Minecraft target matrix containing each exact version, Java toolchain, distribution kind, runtime and integration project paths, and linked Dokka source ownership.
That matrix derives aggregate documentation dependencies, loaded-client sequencing, remap sequencing, publishable runtime selection, artifact coordinates, and per-project toolchains; `verifyMinecraftFabricTargetMatrix` rejects an included version project or linked source boundary that is missing from the matrix.
Configuration on demand is enabled so a targeted API, core, headless, documentation-helper, or benchmark task does not configure all 30 Loom projects.
Each targeted integration project explicitly evaluates its paired runtime project before reading that runtime's compiled source-set output, so an isolated integration task retains the exact Loom-provided Minecraft classpath under configuration on demand.
Full `check`, publication, Qodana, and loaded-game commands still select and configure every required target through their real project and task dependencies.
Minecraft client verification serializes every shared Loom asset preparation task and client launch selected in the task graph so parallel Gradle execution cannot race on Loom's global asset cache or the native client environment.
The official-mapping `remapJar` tasks are also serialized across the remapped targets because each concurrent remapper retains a complete mapped game graph and can exhaust a hosted CI runner's heap.

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
The workflow makes every required Java toolchain available inside the Qodana container so Gradle's IDE importer can resolve the Java 17, Java 21, and Java 25 source-set models and their dependencies.
It assembles Loom dependencies before inspection, then releases only the root aggregate build outputs while retaining subproject Loom outputs and dependency caches so Qodana can import every linked version module and has enough hosted-runner disk for its own project indexes and downloaded sources.
Static-analysis rules are enabled when they produce actionable improvements; rules that systematically make code less clear are disabled with a durable rationale in the checked-in configuration.

The nonpublished `integration:minecraft-fabric-26.2` and `integration:minecraft-fabric-26.1` modules compile the same neutral loaded-client suite against their exact game and Fabric API dependencies.
`./gradlew :integration:minecraft-fabric-26.2:runClientGameTest` fixes the viewport, GUI scale, locale, resource profile, and pointer state, then requires exact native-Screen, Fabric-adapter, and headless ARGB equality before writing build-only evidence.
`./gradlew :integration:minecraft-fabric-26.1:runClientGameTest` applies the same acceptance conditions to 26.1 and writes a version-qualified build receipt.
The nonpublished `integration:minecraft-fabric-1.21.11` through `integration:minecraft-fabric-1.20.6` modules compile the complete shared legacy Java 21 loaded-client suite and the matching input-generation and version-name roots against their remapped adapters and exact Fabric API dependencies.
Minecraft 1.21.4 and later use the Fabric Client GameTest adapter source root, while 1.21.3 through 1.20.6 use the standalone client-entrypoint root because their exact official Fabric API fixtures predate that module.
Fabric API is confined to these integration modules; the published runtimes do not depend on it.
`./gradlew :integration:minecraft-fabric-1.21.11:runClientGameTest :integration:minecraft-fabric-1.21.10:runClientGameTest :integration:minecraft-fabric-1.21.9:runClientGameTest :integration:minecraft-fabric-1.21.8:runClientGameTest :integration:minecraft-fabric-1.21.7:runClientGameTest :integration:minecraft-fabric-1.21.6:runClientGameTest :integration:minecraft-fabric-1.21.5:runClientGameTest :integration:minecraft-fabric-1.21.4:runClientGameTest :integration:minecraft-fabric-1.21.3:runClientGameTest :integration:minecraft-fabric-1.21.2:runClientGameTest :integration:minecraft-fabric-1.21.1:runClientGameTest :integration:minecraft-fabric-1.21:runClientGameTest :integration:minecraft-fabric-1.20.6:runClientGameTest` writes version-qualified build evidence after exercising all thirteen adapters inside their actual clients.
`./gradlew :integration:minecraft-fabric-1.21.11:runProductionClientGameTest :integration:minecraft-fabric-1.21.10:runProductionClientGameTest :integration:minecraft-fabric-1.21.9:runProductionClientGameTest :integration:minecraft-fabric-1.21.8:runProductionClientGameTest :integration:minecraft-fabric-1.21.7:runProductionClientGameTest :integration:minecraft-fabric-1.21.6:runProductionClientGameTest :integration:minecraft-fabric-1.21.5:runProductionClientGameTest :integration:minecraft-fabric-1.21.4:runProductionClientGameTest :integration:minecraft-fabric-1.21.3:runProductionClientGameTest :integration:minecraft-fabric-1.21.2:runProductionClientGameTest :integration:minecraft-fabric-1.21.1:runProductionClientGameTest :integration:minecraft-fabric-1.21:runProductionClientGameTest :integration:minecraft-fabric-1.20.6:runProductionClientGameTest` packages and remaps each integration test Mod and runtime Mod, then repeats the loaded suite from those production jars with their nested common runtime jars.

The nonpublished `integration:docs` module owns two source-safe showcase tasks.
`./gradlew :integration:docs:checkComponentShowcase` depends on that loaded GameTest, verifies its receipt and image hashes, stages each compiled dedicated minimal component `ScreenDefinition` with its complete Fabric/headless-equal frame, and checks freshness without modifying repository files.
`./gradlew :integration:docs:generateComponentShowcase` performs the same parity preflight and synchronizes the combined `docs/components.md` showcase, parity receipt, PNG files, and anchored root README region.
Generated output is owned by the showcase generator; manual edits are reported as stale by the checker.

Run `./gradlew :quality:benchmarks:jmh` for the temporary JSON report and follow the methodology and acceptance gates in [Rendering performance](performance.md).
