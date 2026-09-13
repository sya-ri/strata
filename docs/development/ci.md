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

## JVM shards and reusable inputs

The planner numerically sorts paired targets and deals them across bounded shards with fail-fast disabled.
Documentation checks run exactly once on the shard owning their explicitly declared native input; missing or ambiguous ownership fails planning.
Common checks include isolated CPU-font workers; native font comparisons stay with their integration targets.
Prose-only changes outside compiled documentation contracts do not launch the JVM workflow.

| Cache | Identity and access |
| --- | --- |
| Gradle user home | Common/Minecraft writers use strict job matching and write only after successful `master` runs. Workflow checks, coverage, Qodana, Documentation, and PR jobs restore read-only; readers may use the newest compatible Linux job cache. Loom state is excluded. |
| Project-local Loom | Exact OS, shard, and complete selected build-model hash. A miss regenerates from authoritative inputs; only a successful `master` miss saves a replacement. |
| Qodana project-local Loom | Key the complete discovered inventory; fall back to the newest Minecraft cache on a miss and rebuild missing content. Only a successful `master` run saves a missing exact entry, after analysis, model verification, and report upload. |

Model hashes include the catalog, wrapper, Gradle properties, root build/settings, and selected version build scripts.
Minecraft assets remain upstream inputs instead of large per-shard archives.
Configuration cache is not globally enabled: versioned resource expansion captures `Project`, although the targeted common runtime check supports reuse.
Superseded JVM and Qodana runs on the same ref are cancelled.

## Qodana model

Qodana uses its recommended JVM profile without a baseline and receives every catalog-declared Java toolchain.
The workflow explicitly selects `qodana-jvm-community` in native mode so analysis can use the installed toolchains and restored Gradle user home.
One `--no-daemon` Gradle invocation compiles `classes` and `gametestClasses`, assembles the five plain common jars required by Loom's nested-library model, and generates the IDEA model.
Its JVM exits before analysis; compiled inputs remain available without assembling remapped distributions.

Bootstrap disables configuration on demand and sets `strata.completeIdeaModel` plus `fabric.loom.ci`.
The latter preserves mapped binaries without optional source remapping.
The generated IDEA model assigns linked source roots, real compile/test/GameTest classpaths, and language levels to each owner; `rootJavaProjects` opens that model directly.
Bootstrap may replace its disposable `.idea`/`*.iml` outputs between revisions.
The workflow validates every discovered owner so an incomplete import cannot pass through exclusions.

Before analysis, disk reclamation runs only when free space is below 40 GiB; free space is logged again afterward.
The IDE cache is removed after analysis; enabling persistence requires a verified complete import and a key covering all model inputs.
Disable an inspection only with an actionable rationale in the checked-in configuration.

## Controller regression checks

The independent `Workflow checks` job runs alongside Gradle checks: pinned official actionlint, `bash -n` on release scripts, and release, Java-inventory, and CI-model regressions.
It retains complete Git history and catalog-selected Java toolchains because release fixtures invoke Gradle in temporary checkouts.
These cover source/tag/ruleset drift, artifact and deployment binding, immutable-subtree comparison, receipt drift, pagination/order, and bounded public polling.
Benchmark procedures belong in [performance](performance.md#benchmark-methodology).

## Documentation ownership

Pages freezes controller/release identities in a read-only job, then runs independent controller-site and immutable-release producers concurrently.
Both revalidate those identities before building; deployment requires both successful artifacts, byte equivalence, and final current-master verification.
See [Pages publication](release.md#pages-artifacts-and-deployment) and [documentation ownership](documentation.md#documentation-ownership).

## Evidence

Caches contain reusable intermediates under complete input identities.
Recreate worlds, screenshots, parity receipts, generated documentation, and quality reports on the selected revision.
Protected release invocations and immutable-tag Pages reconstruction use `--no-build-cache`.
