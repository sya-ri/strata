# Build and release

The checked-in wrapper and version catalog are the source of truth for build-tool and dependency versions.
Common modules target the baseline Java toolchain; version-specific Minecraft modules target the toolchain required by their game version.
The `runtime:minecraft-fabric-26.2` module targets Java 25, uses the version-catalog Fabric Loom plugin in no-remap mode for the 26.2 unobfuscated client, and packages the `api`, `runtime:core`, `runtime:headless`, and `runtime:minecraft` jars under `META-INF/jars` exactly once.
Its Fabric metadata declares the fixed Loader, Minecraft, and Fabric Language Kotlin runtime requirements; no Fabric API dependency is used.

Every Kotlin compilation uses explicit API mode and treats warnings as errors.
Detekt and Kotlinter are applied to all project modules.
Kover exposes HTML and XML reports without enforcing a coverage threshold.
Run aggregate coverage with `./gradlew koverHtmlReport koverXmlReport -Pkover`; each report task first runs the ordinary JVM test suite selected by `koverJvmTests`.

Each publishable JVM module has a Maven publication with source and Javadoc artifacts, MIT license metadata, SCM metadata, and an optional in-memory signing setup.
Provide `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, optional `signingInMemoryKeyId`, and optional `signingInMemoryKeyPassword` Gradle properties when publishing to Maven Central.
Environment variables use the `ORG_GRADLE_PROJECT_` prefix followed by the same property name.

Qodana runs its recommended JVM inspection profile in CI without a baseline.
Static-analysis rules are enabled when they produce actionable improvements; rules that systematically make code less clear are disabled with a durable rationale in the checked-in configuration.

The nonpublished `integration:minecraft-fabric-26.2` module owns the loaded client parity test.
`./gradlew :integration:minecraft-fabric-26.2:runClientGameTest` fixes the viewport, GUI scale, locale, resource profile, and pointer state, then requires exact native-Screen, Fabric-adapter, and headless ARGB equality before writing build-only evidence.

The nonpublished `integration:docs` module owns two source-safe showcase tasks.
`./gradlew :integration:docs:checkComponentShowcase` depends on that loaded GameTest, verifies its receipt and image hashes, stages the compiled Minecraft-component scenario sources and verified crops, and checks freshness without modifying repository files.
`./gradlew :integration:docs:generateComponentShowcase` performs the same parity preflight and synchronizes the checked showcase Markdown, parity receipt, PNG files, and anchored root README region.
Generated output is owned by the showcase generator; manual edits are reported as stale by the checker.
