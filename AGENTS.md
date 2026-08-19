# Strata implementation invariants

This file records durable project invariants for future implementers.
The canonical architecture and build details live in [docs/architecture.md](docs/architecture.md) and [docs/build.md](docs/build.md).
Follow [docs/minecraft-versions.md](docs/minecraft-versions.md) when adding a supported game version.
Update the canonical document when its contract changes.

## Architecture and API

- Keep the module DAG acyclic: `api` is the platform-neutral public SPI; published `runtime:core` depends on it and owns the retained engine; `runtime:headless` and `runtime:minecraft` may depend on the public contracts and core as their implementation requires; the versioned Fabric runtime may depend on both `api` and `runtime:minecraft`.
- Include a module only when it contains working, tested behavior.
  Do not publish empty artifacts or add placeholder source sets, tests, adapters, or documentation.
- Keep platform integration at runtime/integration boundaries.
  Public SPI code must not dispatch on concrete component types; use extensible contracts.
- Modifiers are active behavior, while parent data is exposed only by the layout scope that consumes it.
  Do not turn either into a passive settings bag or hidden global state.
- Preserve null-safety and do not use `!!` in production or test code.

## Source and documentation

- Use imports instead of body-qualified type names (no body FQCNs).
- Write boolean negation with `.not()` rather than the `!` prefix operator.
- Express ordering comparisons with `<` and `<=`; reverse operands instead of using `>` or `>=`.
  Extract side-effectful operands before reversing them so evaluation order remains explicit.
- Do not discriminate domain state or component kinds with string-literal comparisons or string/number constants.
  Decode external values at the adapter boundary into enums, sealed hierarchies, or value types.
  Replacing a `const val` with a `val` does not make a discriminator type-safe; use a compile-time constant only when an external API requires one.
- Public, protected, and internal classes and methods require KDoc that describes the contract, inputs, outputs, ownership, threading, and failure behavior.
  Test methods are exempt, and overrides may inherit the contract of the method they implement.
  Comments in implementation code explain only non-obvious rationale or invariants.
- Keep at most one named top-level type per source file.
  A file containing only extensions may group functions for one domain and receiver.
  All documentation and code text is English.
- Do not hard-wrap prose at a fixed column.
  Break documentation lines only at semantic boundaries, normally one sentence per line.
- Verify dependency, plugin, and external-tool versions against their current primary documentation before adding or updating them.
  Reference GitHub Actions by their current stable major tag when the action publishes one.
- Keep dependency and plugin versions in the Gradle version catalog.
  Do not copy external tool versions into prose documentation that would drift independently from build configuration.

## Reader documentation

- Keep the root README sufficient to understand the problem Strata solves, its verified features, installation, a minimal compiled example, the module choices, and the next documentation links without opening another file.
- Back README API examples with compilation tests and render README images from shipped examples.
  Do not advertise behavior before its test and implementation are part of the same release.
- Generate the component showcase from compiled scenario metadata.
  Each component page contains its generated image, corresponding source, modifier and parent-scope guidance, and a component tree hidden inside a `details` element.

## Verification

- Keep tests Minecraft-independent wherever the behavior does not require a loaded game.
  Use a Fabric GameTest only when the behavior genuinely needs the game environment.
- Keep Detekt, Kotlinter, Qodana, Kotlin explicit API mode, warnings-as-errors, ABI validation, and Kover HTML/XML reports enabled.
  Kover reports must run after JVM tests and do not enforce a coverage threshold.
- Before review, run `./gradlew check koverHtmlReport koverXmlReport -Pkover` and inspect publication metadata with `./gradlew publishToMavenLocal` when publication code changes.
- For Minecraft-specific facts, use the Minecraft evidence tools first, then inspect authoritative local files or search authoritative web sources.
  Do not infer version-sensitive behavior.

## Git workflow

- Commit coherent green slices instead of accumulating the whole release in one commit.
- Follow the repository's English imperative commit-message style without adding a prefix.
- Do not use `codex` in a branch name or pull-request title.
