# Continuous integration

CI distributes the checks from [local verification](build.md) and validates each analysis/publication boundary.
Use this document when changing workflow topology or caches; [release publication](release.md) owns external mutations.

## Build configuration and concurrency

The typed Minecraft target matrix owns versions, toolchains, distributions, paired projects, and linked Dokka sources.
It derives task selection, artifact coordinates, sequencing, and runtime Java compatibility.
`verifyMinecraftFabricTargetMatrix` rejects missing owners.

Configuration on demand is disabled because the Kotlin/JS workspace and dependency lock require a complete project model.
Integration projects evaluate their paired runtime before reading compiled output; documentation launchers inherit dependencies from their runtime classpath.
Full verification selects every required target through task dependencies.

Two single-permit build services serialize shared work:

| Work | Reason |
| --- | --- |
| Selected Loom asset preparation and client launches | Protect the shared asset cache and native client environment; assets precede clients. |
| Official-mapping `remapJar` | Bound concurrent mapped-game graphs on hosted runners. |

Client tasks seed disposable project-build run directories after cleanup, disabling accessibility onboarding, tutorials, narration, and sound.
The Canvas/Slot oracle temporarily hides the HUD and restores it even on failure, preventing late tutorial/recipe toasts from covering pixels.
Ordinary client launches and personal settings are unaffected; frame fences and pixel assertions remain enabled.

## Changes and job selection

Heavy checks run on pull requests, with a manual full run available through `workflow_dispatch`.
Merging a PR does not repeat JVM or Qodana verification.
The always-running planner and `CI result` job distinguish planned skips from failures, cancellation, and unexpected skipped checks.

| Changed input | Selected work |
| --- | --- |
| Publication tools or workflows | Syntax, controller behavior fixtures, and isolated publication-tool compilation |
| Prose and links | Documentation and Skill consistency |
| Documentation generation, examples, images, or Dokka | Compiled documentation and generated-output checks |
| One Minecraft runtime or shared source | Every version using that source |
| API, common runtime, or build-wide settings | Common checks and all affected runtimes |
| Web | Linux unit/demo checks and Windows browser parity |
| Unrecognized input | Full verification |

`writeCiSourceModel` exports the configured runtime/integration source roots from the existing Gradle target model.
Shared-source selection uses that output; deletions and both sides of renames participate in the diff.
The planner numerically orders selected versions into bounded shards, with fail-fast disabled.
Documentation runs once on the shard owning its declared native input.
Common checks and Kover share one Gradle invocation so JVM tests run once.
Web unit tests run once on Linux; Windows retains the platform-specific browser checks.

Gradle dependency caches may be saved and restored inside PRs, subject to GitHub's branch/ref isolation.
Loom project caches use the OS, selected projects, and build-model hash; successful misses save their regenerated inputs.
These caches contain dependencies and build intermediates, not test worlds, screenshots, parity receipts, or reports.
Acceptance evidence is generated for the selected revision.
A signed release's prepared artifacts are immutable publication inputs, as defined in [release publication](release.md).
Superseded JVM and Qodana runs on the same ref are cancelled.

## Qodana model

Qodana runs for PRs changing analyzed code or analysis configuration, manual full runs, and first release preparation.
It uses its recommended JVM profile without a baseline and receives every catalog-declared Java toolchain.
The workflow explicitly selects `qodana-jvm-community` in native mode so analysis can use the installed toolchains and restored Gradle user home.
One `--no-daemon` Gradle invocation compiles `classes` and `gametestClasses`, assembles the five plain common jars required by Loom's nested-library model, and generates the IDEA model.
Its JVM exits before analysis; compiled inputs remain available without assembling remapped distributions.
API/core use their `jvmJar` tasks, and multiplatform JVM modules expose common and JVM production/test roots with their real JVM classpaths.
Qodana's JVM model covers that JVM view; JavaScript-specific sources are checked by Detekt, the Kotlin/JS compiler, and browser tests.
The generated model co-locates common and JVM declarations without KMP source-set relationships, so `UnusedSymbol` can miss real calls across `expect`/`actual` declarations and typealiases.
The configuration lists only the affected bridge files for that inspection; other inspections and the zero failure threshold remain enabled.
Before extending that list, verify real callers and remove unused operations from every target; remove the exceptions when the analysis model can resolve those relationships.

Bootstrap disables configuration on demand and sets `strata.completeIdeaModel` plus `fabric.loom.ci`.
The latter preserves mapped binaries without optional source remapping.
The generated IDEA model assigns linked source roots, real compile/test/GameTest classpaths, and language levels to each owner; `rootJavaProjects` opens that model directly.
Bootstrap may replace its disposable `.idea`/`*.iml` outputs between revisions.
The workflow validates every discovered owner so an incomplete import cannot pass through exclusions.

Before analysis, disk reclamation runs only when free space is below 40 GiB; free space is logged again afterward.
The IDE cache is removed after analysis; enabling persistence requires a verified complete import and a key covering all model inputs.
Disable an inspection only with an actionable rationale in the checked-in configuration.

## Controller regression checks

`Workflow checks` runs actionlint, shell syntax checks, Python behavior tests, and Java/CI/Qodana model fixtures without starting Minecraft.
It compiles the isolated publication tools without loading product projects.
Behavior tests cover changed-path ownership, missing or altered prepared artifacts, successful preparation followed by a failed destination, immutable-release recovery, service conflicts, and receipt restoration.
Checks do not enforce workflow prose or source statement order.

## Distribution and documentation

[Release publication](release.md) owns first-time full validation, immutable preparation, per-destination retries, and moderation monitoring.
Documentation inputs trigger Pages separately after merge; the checked site is generated once and old rendered versions come from `gh-pages`.
See [Pages publication](release.md#pages-artifacts-and-deployment) and [documentation ownership](documentation.md#documentation-ownership).
Wait for CI using `gh run watch <run-id> --exit-status`; inspect logs after completion or failure.
