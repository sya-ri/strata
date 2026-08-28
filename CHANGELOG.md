# Changelog

All notable changes to Strata are documented in this file.

## 0.1.1 - 2026-08-28

### Added

- A separate Fabric runtime for Minecraft 1.20, extending the supported release floor from Minecraft 1.20.1 to 1.20.
- Unicode and resource-pack font selection for Text, common labels, tooltips, and both TextField forms while retaining the existing overloads and TextStyle.
- Typed multiline Text layout with structural wrapping, line limits, clipping or ellipsis, and preserved Unicode/font provenance; existing Text calls remain single-line.
- General-purpose TextArea editing with canonical LF state, Unicode scalar and visual-line navigation, bounded IME composition, and an owned ScrollState shared with external scrollbars.
- Portable scoped paint clips with default-method compatibility for existing PaintScope implementations.
- Immutable font-resource snapshots, standard bitmap/space/reference/Unihex/TrueType providers, and the optional `runtime:minecraft-fonts-lwjgl` CPU backend with bounded per-host caches and explicit native dependency contracts.
- Native behavior for signed and signed-zero TrueType settings, including atlas fallback, signed widths, and prepared-text bounds, subject to the documented native-conversion safety boundary.
- Exact native glyph metrics, raw glyph data, and layout checks, plus exact Fabric/headless output at GUI scales 1, 2, and 3; final native image differences require independent evidence of a GPU effect.

### Fixed

- TextField scalar-boundary editing, supplementary-character UTF-16 limits, scrolled pointer placement, and duplicate cursor movement after state notifications.
- Delivered IME composition isolation and native text-input focus ownership on adapters with Minecraft preedit events.
- Consecutive input events synchronize dirty retained geometry before hit testing, preventing scroll-to-move or scroll-to-press failures between rendered frames.
- Mixed-font Arabic shaping preserves original font selection across contractions and bidirectional reordering.
- Repeated Fabric screen opens reuse one immutable profile generation; reload and terminal close invalidate it without changing snapshots still used by open hosts.
- Font resource enumeration, decoding, reference expansion, and decompression enforce explicit per-input and aggregate work limits, including rejected input.

### Compatibility

- `UiText.WithFont` and `DrawCommand.SampledImage` intentionally expand existing sealed hierarchies. Exhaustive visitors must handle the new cases before recompilation; previously compiled visitors can fail when they receive those cases.
- Existing component overloads, TextStyle, and JVM signatures remain available. This member compatibility does not imply source or behavioral compatibility for old text visitors or custom rendering backends.

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
