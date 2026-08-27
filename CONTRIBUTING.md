# Contributing to Strata

Read the [implementation invariants](AGENTS.md) and [Architecture](docs/architecture.md) before changing public contracts or module boundaries.
[Build and release](docs/build.md) is the canonical reference for build configuration and verification.

## Development setup

Run commands from the repository root with the checked-in Gradle wrapper: `./gradlew`, or `.\gradlew.bat` in PowerShell.
Install the Java toolchains declared by the build: common modules use the baseline toolchain, while each Minecraft adapter uses its target's required toolchain.
Automatic toolchain downloads are disabled.
The wrapper and [version catalog](gradle/libs.versions.toml) define build-tool, plugin, and dependency versions.
Verify additions and updates against current primary documentation, and keep dependency and plugin versions in the catalog rather than copying them into prose or module scripts.

## Contribution standards

- Preserve the acyclic module boundaries and keep platform integration inside runtime and integration modules.
  Add modules only with working behavior and tests, and extend the public contracts without dispatching on concrete component types.
- Standard built-ins must have one focused UI responsibility and at least two natural independent uses.
  Review whether a proposal is too specialized or can be composed from existing primitives; this restriction does not apply to downstream application components.
- Preserve null-safety without `!!`, use imports instead of body-qualified type names, and write boolean negation with `.not()`.
  Express ordering with `<` and `<=`, preserving evaluation order when reversing operands.
  Decode external discriminators into enums, sealed hierarchies, or value types at adapter boundaries.
- Keep at most one named top-level type per source file; extension-only files may group functions for one domain and receiver.
- Document public, protected, and internal classes and methods with KDoc covering contracts, inputs, outputs, ownership, threading, and failures.
  Test methods are exempt, and overrides may inherit their contract.
- Write code and documentation in English, and break prose at semantic boundaries rather than a fixed column.
  Update the canonical documentation whenever a contract changes, and advertise only implemented, tested behavior.

For Minecraft changes, use the Minecraft evidence tools first, then authoritative local files or primary sources instead of inferring version-sensitive behavior.
Follow [Supporting a new Minecraft version](docs/minecraft-versions.md) when adding an adapter.

## Verification

Keep tests independent of Minecraft whenever a loaded game is unnecessary, and run the affected module checks during development.
Before review, run the full checks and coverage reports:

```shell
./gradlew check koverHtmlReport koverXmlReport -Pkover
```

Keep Detekt, Kotlinter, Qodana, explicit API mode, warnings-as-errors, ABI validation, and both Kover reports enabled.
Coverage reports run after JVM tests without a coverage threshold.
Loaded worlds, screenshots, parity receipts, generated documentation, and analysis or coverage reports must be recreated and verified on the current revision, not accepted from a build cache.
When publication code changes, also run `./gradlew publishToMavenLocal` and inspect the publication metadata.

For documentation and public-skill changes, run these focused checks:

```shell
./gradlew :integration:docs:checkStrataSkill :integration:docs:checkDocumentationLinks
```

The anchored README API example and component showcase are generated regions; do not edit their contents by hand.
Update their source examples and use the generation tasks described in [Build and release](docs/build.md).
The showcase check also requires fresh loaded-game parity evidence.

## Commits and pull requests

Commit coherent changes with passing checks rather than accumulating an entire release in one commit.
Inspect recent commits and follow the repository's English imperative commit-message style without a prefix.
Do not use `codex` in branch names or pull-request titles.
