# Changelog

All notable changes to Strata are documented in this file.

## 0.1.1 - 2026-08-27

### Added

- A separate Fabric runtime for Minecraft 1.20, extending the supported release floor from Minecraft 1.20.1 to 1.20 without changing the public API or common runtime behavior.

## 0.1.0 - 2026-08-25

### Added

- API-only declarative screen authoring with `ScreenDefinition.open()` and the Java `Screens.open(definition)` facade.
- Row, Column, Stack, Grid, and Spacer layouts; 16 profile-backed and data-oriented components; active modifiers; caller-owned state; resource sources; inventory bindings; and public Element and Node extension SPI.
- Retained core, deterministic headless rendering, common Minecraft integration, and separate client Fabric runtimes for Minecraft 1.20.1 through 1.21.11, 26.1, and 26.2.
- Loaded Minecraft comparison, production-jar, inventory synchronization, lifecycle, performance, retention, and documentation-generation tests.
- Generated component showcase, Dokka GitHub Pages site, public `skills/strata` Codex skill, Maven Central publication, GitHub Release artifacts, and Modrinth runtime distribution.

### Known limitations

- The verified bitmap font path supports printable ASCII with the regular Minecraft glyph sheet; forced Unicode and multi-resource font stacks are not supported.
- Resource-backed images retain their declared logical dimensions and use nearest sampling.
