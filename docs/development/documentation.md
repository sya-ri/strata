# Documentation maintenance

Documentation is organized by reader purpose: guides explain application use, references define public extension contracts, examples show complete compositions, and development documents describe Strata implementation and operations.
The [documentation index](../README.md) owns navigation.
Release notes and publication introductions have separate audiences and must not become general feature guides.

## Writing and canonical ownership

Begin each page with its purpose and intended reader, then introduce ordinary use before detailed constraints.
Keep a contract in one canonical document and link to it from related guides.
Use the [Dokka API reference](https://gh.s7a.dev/strata/) for declarations and overload details; the generated component overview remains a single browsable collection of images and examples.
Keep version-specific migration in release notes and adapter differences in the compatibility or adapter-development references.
Write documentation and code text in English, with prose broken at semantic boundaries rather than a fixed column.

The root README must explain the library, verified capabilities, installation, a minimal compiled example, module choices, and next reading links on its own.
Do not advertise unimplemented behavior.
Layout examples use structural arrangement rather than copied coordinates; padding of 20 logical pixels or more requires a geometry or native-frame rationale in the showcase source.

## Headless showcase generation

The nonpublished `integration:docs` module owns two showcase tasks that render the compiled API-only examples on the CPU without launching Minecraft or creating a GPU context.
Run these isolated cross-project tasks with configuration-on-demand disabled so every project contributing compiled examples and renderers is configured before Gradle resolves its classpath.
`./gradlew :integration:docs:checkComponentShowcase --no-configure-on-demand` renders fresh headless frames into staging and checks documentation freshness without modifying repository files.
`./gradlew :integration:docs:generateComponentShowcase --no-configure-on-demand` performs the same rendering and synchronizes `docs/reference/components.md`, `docs/examples/screens.md`, PNG files, `docs/components/headless-render.properties`, and the anchored root README region.
The Canvas example uses its portable CPU source; native texture and custom-renderer acceptance remain separate loaded-game checks.
The TiledImage example uses twelve independent immutable CPU tiles and a content-position overlay; the loaded component parity gate renders the same compiled definition through Fabric.
Generated output is owned by the showcase generator; manual edits are reported as stale by the checker.

## Compatibility reference

The root `./gradlew :generateCompatibilityDocumentation` task writes `docs/reference/compatibility.md` from the typed Minecraft target matrix and publication artifact naming rules.
Run `./gradlew :checkCompatibilityDocumentation` to compare the checked reference with that model without changing files.
The reference records supported targets, Java requirements, distribution mapping, and artifacts; it does not infer a successful native verification run from a build-model entry.
Update the typed matrix when target ownership changes, then regenerate the reference rather than editing its rows.

## Resource inputs and receipts

Both tasks read an explicit Minecraft client archive, asset index, indexed objects directory, and version manifest.
They always execute without build-cache reuse; Gradle records the objects directory's location rather than recursively fingerprinting its shared contents, and the launcher verifies every consumed input before publishing its receipt.
By default, Gradle provisions these raw resources through dedicated asset publications from `integration:minecraft-fabric-26.2`; provisioning may download missing official assets but does not start a game process or add Minecraft, Fabric, OpenGL, or GLFW classes to the documentation runtime classpath.
To supply existing resources instead, set all four properties; relative paths resolve from the repository root, and supplying all four avoids the integration project's asset-provisioning tasks:

```shell
./gradlew :integration:docs:generateComponentShowcase --no-configure-on-demand -Pstrata.showcase.clientJar=assets/minecraft-client.jar -Pstrata.showcase.assetIndex=assets/index.json -Pstrata.showcase.assetObjects=assets/objects -Pstrata.showcase.versionManifest=assets/version.json
```

The same properties apply to `checkComponentShowcase`.
Inputs remain read-only, must be regular files or the objects directory without symbolic links, and are checked against the version manifest and indexed object hashes.
The complete manifest and asset index validate each generation but are not portable receipt identities.
Only the inventory screen uses native image evidence: the launcher explicitly supplies `docs/evidence/minecraft-26.2-inventory.png` and its `.properties` receipt because that example requires a live server-backed binding.
Generation verifies the inventory image dimensions, Minecraft version, image hash, and current compiled example's LF-normalized source hash before copying it; it never launches a game or server to refresh that input.
The deterministic headless receipt records the immutable client, selection contract, logical resource path sets, consumed resource and source hashes, logical viewports, GUI scales, physical PNG dimensions, and each image's origin and hash without absolute paths or timestamps.
Changes to unrelated existing asset-index mappings do not make unchanged output stale when those recorded identities and logical path sets remain the same.
`Text`, `TextField`, and `TextArea` use GUI scale 2 at unchanged logical viewports; other components, the overview, and complete screens use scale 1.
This density controls original glyph sampling during rendering, not image enlargement afterward.
Animated examples publish the canonical frame at time zero; loaded verification requires exact full-frame pixels for a supported discrete animation phase, which may differ from that stored phase.

## Independent native acceptance

Native acceptance is independent of generation: `./gradlew :integration:docs:generateMinecraftShowcaseEvidence` runs the loaded gate and refreshes `docs/evidence/minecraft-26.2-parity.properties` plus the inventory image and receipt.
`./gradlew :integration:docs:checkMinecraftShowcaseParity` compares fresh native evidence with fresh headless showcase output, including each component's logical viewport and GUI scale.
The module's `check` task requires both documentation freshness and this native acceptance gate; the two targeted headless generation and freshness tasks do not require a loaded-game run.

The loaded 26.2 client GameTest requires exact ARGB equality among deterministic native screens, their Fabric-adapter reconstructions, and common headless frames at each locked 320 by 180, 320 by 240, or 64 by 64 acceptance viewport.
It covers `ConfirmScreen`, `DirectJoinServerScreen`, `ContainerScreen`, an actual `ObjectSelectionList`, `SocialInteractionsScreen`, native `PlayerFaceExtractor`, an integrated-server synchronized inventory, and custom industrial and progression Mod screens, then keeps those full-screen acceptance frames separate from the component showcase evidence.
For every standard component, the loaded GameTest also evaluates a dedicated minimal `ScreenDefinition` independently through the Fabric and headless runtimes, requires exact full-frame ARGB equality with a matching animation phase when the component animates, and writes the canonical headless frame at time zero with its logical viewport, GUI scale, and hashes below the build directory.
The Social comparison composes the public primitives with the active social panel and search assets, a compact profile-colored TextField, and PlayerHead, then requires the complete 320 by 240 native, Fabric, and headless images to match exactly.
The progression example keeps its purpose-specific graph downstream while composing active advancement textures through the general source-region Image API; the industrial screen similarly uses a replaceable Mod resource rather than a domain-specific standard component.

## Compiled examples and public skill

The same module owns the public `skills/strata` package and its API-only compiled examples.
`./gradlew :integration:docs:checkStrataSkill :integration:docs:checkDocumentationLinks` discovers component and Modifier overloads from compiled API classes, pairs them with exact Kotlin source declarations, checks state and binding declarations against compiled public member fingerprints, verifies generated references byte-for-byte, and checks every repository-local README, docs, and skill link without changing tracked files.
`./gradlew :integration:docs:generateStrataSkill` deliberately synchronizes the five generated skill references, the anchored README installation and API-only example regions, and the canonical `docs/publication/modrinth-project.md` body after a release-version, API, or example change.
The skill examples use a separate source set whose compile classpath contains only `:api`; ordinary application examples therefore cannot acquire a runtime import transitively.

The README marker pairs own installation, the API opening example, the component showcase, and the animated player-list demo.
Preserve those markers and update their source examples or templates instead of hand-editing generated content.
Keep the public skill's existing directory structure and update its links and generated references when documentation moves.

## README code-and-screen demo

The nonpublished `integration:docs` module compiles the player-list stages from `src/readmeExamples/kotlin` against `:api` alone and also includes those exact sources in its generator.
Run `./gradlew :integration:docs:generateReadmeDemo --no-configure-on-demand` to render fresh Minecraft-backed screens, compose the displayed source, and synchronize `docs/readme-demo` plus the anchored root README region.
Run `./gradlew :integration:docs:checkReadmeDemo --no-configure-on-demand` to recreate and compare every artifact without modifying tracked files; the module's `check` includes this gate.
These tasks reuse the component showcase's four `strata.showcase` asset properties and their default Loom publications, but do not require a loaded client or native inventory evidence.
Each invocation uses fixed offline default skins, a zero frame time, a fixed viewport and GUI scale, and the bundled JetBrains Mono typeface with its original license.
The font version is owned by the version catalog; the render receipt records its actual bytes along with source, asset, and output hashes.
Source hashes normalize CRLF and CR line endings to LF before UTF-8 encoding, matching excerpt extraction across Windows and Linux; all other source characters and all binary input and output bytes remain significant.
The GIF uses a shared palette, complete opaque frames, explicit delays totaling 24 seconds, and an infinite loop; the final second holds the completed source without change highlighting.
Full-color stills retain the actual headless screen pixels separately from GIF palette conversion.
The preview uses the original Social Interactions panel and standard Minecraft widgets.
The GIF contains only highlighted source and screen pixels: natural row sizing, added content, a fixed outer Column width that every row fills, a separate weight edit that gives text the remaining space, then right and left text alignment while frames, heads, and buttons stay fixed.
The final sequence adds players one at a time at 0.25-second intervals, intentionally exposes the unscrollable list overflow, adds ScrollArea, and then adds an independent Scrollbar sharing the same ScrollState.
The player row stays inline in every compiled sample.
The 1200 by 900 canvas displays each complete source excerpt without scrolling, using the bundled font at 18 pixels with compact indentation; the screen keeps its integer 2x scale.
Fixed wheel inputs demonstrate scrolling before the bar exists and synchronized list and thumb movement afterward; screen positions are sampled without interpolation.

## API site and Pages

The root `./gradlew :dokkaGenerate` task aggregates every published module into `build/dokka/html`.
The Pages workflow uses `docs/publication/dokka-module.md` as the API landing-page introduction instead of the root README.
After a read-only job freezes the source identities, separate controller and immutable-release producers run concurrently and independently revalidate those identities before building.
Deployment waits for both artifacts and verifies their immutable release subtrees byte for byte.
`generateDokkaModuleMarkdown` prepares an ignored build-only include whose GitHub reader links use the selected `strata.sourceRevision`, so tagged API sites link to their matching repository guides.
Reader guides and verified component images stay in the repository and are rendered on GitHub; the current Dokka artifact contains the generated API reference.

Run `./gradlew :integration:docs:checkDokkaPagesStaging` to generate and verify that site.
The staging checker validates links, anchors, and assets in inventoried HTML against the staged tree.
It also checks hard-coded `https://gh.s7a.dev/strata/` targets in source text against real staged files, treating a trailing slash as `index.html`.
`generateDokkaPagesInventory` records sorted public paths, source revision, and source receipt below `build/dokka/html`.

The [release publication contract](release.md#pages-artifacts-and-deployment) owns immutable release subtrees, controller identity, deployment permissions, artifact retention, and public propagation checks.

## Documentation ownership

Keep canonical API and runtime contracts, reader guides, release notes and publication bodies, compiled examples, and deterministic generated images and receipts in Git.
Update generator sources and regenerate checked outputs instead of editing generated documents by hand.
Keep task plans, working notes, status reports, and unfinished drafts under the ignored `build/` directory; promote durable decisions into the relevant canonical guide when the work is complete.
Do not retain release-incident timelines or external-service status snapshots in reader guides; encode any required recovery boundary in executable contracts and tests.
Loaded worlds, transient screenshots, raw benchmark output, and test or coverage reports remain untracked build outputs.
