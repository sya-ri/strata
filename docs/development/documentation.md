# Documentation maintenance

Use this page when editing documentation, comments, or their generators.
The [index](../README.md) owns navigation; each contract has one canonical home.

## Writing and canonical ownership

Choose detail by the reader's task:

| Surface | Reader needs | Include |
| --- | --- | --- |
| README and index | Decide whether Strata fits; find the next step | Verified capabilities, installation, a minimal compiled example, module choices, links. |
| Guide | Complete an application task | Ordinary use, examples, constraints that affect that choice. |
| Reference and KDoc | Call or implement a contract correctly | Exact semantics, edge cases, ownership, threading, and failures where relevant. |
| Development document | Change or operate Strata | Invariants, procedures, verification boundaries, rationale that code cannot convey. |
| AGENTS.md and skill entrypoint | Make AI implementation decisions | Project-specific rules and routing to details needed for the current task. |
| Release notes | Upgrade from an earlier version | User-visible changes, compatibility, migration. |

Lead with the answer or ordinary use; link to specialist detail instead of repeating it.
A short declaration may need only one KDoc sentence.
Enum values need their own explanation only when their name or enclosing contract is insufficient; duplicating adjacent display text is unnecessary.
Use parameter/return tags when they explain units, bounds, ownership, or another fact absent from the signature.
Do not add boilerplate about purity, lack of resources, or every possible failure to simple helpers.
Implementation comments explain non-obvious rationale and invariants; generated markers and license notices remain intact.

Keep exact declarations in [Dokka](https://gh.s7a.dev/strata/) and checked AI references, rather than hand-maintained copies.
Write English prose with line breaks at semantic boundaries, not a fixed column.
Advertise only implemented, tested behavior.
Showcase layout uses structural arrangement; padding of 20 logical pixels or more needs a geometry or native-frame rationale in its source.

## Headless showcase generation

`integration:docs` renders compiled API-only examples on the CPU without launching Minecraft or creating a GPU context.
Disable configuration on demand for the cross-project render tasks:

```shell
./gradlew :integration:docs:generateComponentShowcase --no-configure-on-demand
./gradlew :integration:docs:checkComponentShowcase --no-configure-on-demand
```

Generation owns the component overview, complete screens, their PNGs and receipt, and the README showcase region.
The check rerenders into staging and compares tracked output without modifying it.
Canvas uses a portable CPU source; TiledImage uses immutable independent tiles and an overlay.
Native acceptance is separate.

## Compatibility reference

`./gradlew :generateCompatibilityDocumentation` renders the typed target matrix and artifact rules into `docs/reference/compatibility.md`.
`./gradlew :checkCompatibilityDocumentation` checks freshness.
Edit the matrix, not generated rows; a model entry is not evidence of a successful native run.

## Resource inputs and receipts

Showcase and README render tasks use a client archive, asset index, objects directory, and version manifest.
By default, dedicated Loom asset publications from `integration:minecraft-fabric-26.2` provision them without adding game or graphics classes to the documentation runtime.
To use existing inputs, supply all four properties; relative paths resolve from the repository root:

```shell
./gradlew :integration:docs:generateComponentShowcase --no-configure-on-demand -Pstrata.showcase.clientJar=assets/minecraft-client.jar -Pstrata.showcase.assetIndex=assets/index.json -Pstrata.showcase.assetObjects=assets/objects -Pstrata.showcase.versionManifest=assets/version.json
```

Inputs are read-only regular files or directories without symbolic links.
The launcher verifies manifests and consumed object hashes on every run; render tasks do not reuse build-cache evidence.
Receipts record consumed asset/source identities, logical paths, viewports, GUI scales, output dimensions, origins, and hashes without absolute paths or timestamps.
Unrelated asset-index entries do not invalidate unchanged consumed identities.

The inventory example additionally requires `docs/evidence/minecraft-26.2-inventory.png` and its receipt.
Generation checks dimensions, game version, PNG hash, and the current example's LF-normalized source hash before copying it; refreshing that evidence requires the native task below.
Text previews use GUI scale 2; other showcase images use scale 1.
Animated examples store time zero; native comparison accepts an exact supported animation phase.

## Independent native acceptance

`./gradlew :integration:docs:generateMinecraftShowcaseEvidence` refreshes the native parity receipt and inventory evidence.
`./gradlew :integration:docs:checkMinecraftShowcaseParity` compares fresh native evidence with fresh headless output.
The module's `check` requires both freshness and native acceptance; focused headless tasks do not launch a client.

The loaded 26.2 suite compares native screens, their Fabric reconstructions, and headless frames at fixed viewports with exact ARGB equality.
It separately verifies minimal component definitions, animation phases, native heads, and a server-backed inventory.
Application-specific social rows, industrial controls, and progression graphs remain example compositions.
See [loaded-client verification](build.md#loaded-client-verification) and [font acceptance](font-verification.md#acceptance-evidence) for their independent boundaries.

## Compiled examples and public skill

```shell
./gradlew :integration:docs:generateStrataSkill
./gradlew :integration:docs:checkStrataSkill :integration:docs:checkDocumentationLinks
```

Generation owns the five skill references, README installation/opening-example regions, and Modrinth project body.
The checker pairs source declarations with compiled API inventories, validates state/binding fingerprints, and checks byte-exact freshness and repository-local links.
Skill and README examples compile against `:api` alone.
Preserve the public skill's package structure and README marker pairs; edit their templates or source examples.

## README code-and-screen demo

```shell
./gradlew :integration:docs:generateReadmeDemo --no-configure-on-demand
./gradlew :integration:docs:checkReadmeDemo --no-configure-on-demand
```

The generator compiles `src/readmeExamples/kotlin`, renders its exact player-list stages, and synchronizes `docs/readme-demo` and the README marker region.
It uses the same four asset properties, fixed offline skins, zero frame time, fixed geometry, and the bundled licensed typeface.
No loaded client or inventory evidence is needed.

Receipts hash sources after CRLF/CR-to-LF normalization and retain exact binary input/output hashes.
The looping GIF uses a shared palette, complete opaque frames, explicit delays, and a final hold without change highlighting; full-color stills retain the original screen pixels.
The stages demonstrate natural sizing, outer width, weight, text alignment, overflow, and linked ScrollArea/Scrollbar behavior with fixed wheel inputs.
Visual settings and timings belong in the generator, not a second prose specification.

## API site and Pages

`./gradlew :dokkaGenerate` aggregates published modules into `build/dokka/html`, using `docs/publication/dokka-module.md` as its introduction.
The generated build-only include pins GitHub guide links to `strata.sourceRevision`.
Reader guides and images remain in the repository.

`./gradlew :integration:docs:checkDokkaPagesStaging` generates the site and checks inventoried HTML links, anchors, assets, and hard-coded Pages targets against real staged files.
`generateDokkaPagesInventory` records sorted public paths and source receipts.
The [release contract](release.md#pages-artifacts-and-deployment) owns immutable subtrees, producer identities, deployment permissions, retention, and propagation checks.

## Documentation ownership

Track canonical contracts, guides, release notes, publication bodies, compiled examples, and deterministic generated images/receipts.
Regenerate checked outputs from their owners.
Keep plans, working notes, status reports, drafts, loaded worlds, transient screenshots, benchmarks, and quality reports under ignored `build/` directories.
Promote durable decisions into their canonical document; leave incident timelines and service-status snapshots out of reader guides.
