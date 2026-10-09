# Continuous integration

CI distributes the checks from [local verification](build.md) and validates each analysis/publication boundary.
Use this document when changing workflow topology or caches; [release publication](release.md) owns external mutations.

## Build configuration and concurrency

The typed Minecraft target matrix owns versions, toolchains, distributions, paired projects, and linked Dokka sources.
It derives task selection, artifact coordinates, sequencing, and runtime Java compatibility.
`verifyMinecraftFabricTargetMatrix` rejects missing owners.

Configuration on demand is disabled because the Kotlin/JS workspace and dependency lock require a complete project model.
Web-only checks use `-Pstrata.webOnly=true` to include the complete JavaScript workspace and its JVM parity dependencies without loading Minecraft adapters.
This scope accepts only fully qualified module `check` and `jsTest` tasks; publication and coverage aggregation use the complete build.
An invocation containing only `:ciMinecraftCheck` includes the Minecraft versions selected by `strata.minecraftVersions` and omits the documentation project.
Adding `:integration:docs:checkMinecraftShowcaseParity` retains that project for the native showcase comparison without loading other Minecraft versions.
The separate documentation job includes every runtime for Dokka and only the integration project supplying its assets.
Other task combinations retain the complete project inventory.
Integration projects evaluate their paired runtime before reading compiled output; documentation launchers inherit dependencies from their runtime classpath.
Full verification selects every required target through task dependencies.

Shared build services bound native verification and shared preparation:

| Work | Reason |
| --- | --- |
| Selected Loom asset preparation | Protect the shared asset cache; all selected assets precede clients. |
| Client launches | `strata.minecraftClientParallelism=auto` is the default; resource admission adapts during execution. A positive integer selects a fixed ceiling. |
| Clients belonging to the same Minecraft version | Keep development, production and published-coordinate runs exclusive within their target. |
| Official-mapping `remapJar` | Bound concurrent mapped-game graphs on hosted runners. |

Correctness verification automatically adapts new client admission to current free physical RAM and system CPU load.
The automatic ceiling uses half the available processors and leaves one Gradle worker for prerequisite tasks; there is no fixed two-client ceiling.
Admission rechecks resources while waiting and before every launch, and task completion wakes waiting clients.
Lower capacity stops additional launches until running clients finish; it does not cancel or restart an admitted test.
The memory policy reserves 2 GiB for the desktop and unallocated Gradle heap, then budgets each client for its maximum heap plus 1 GiB of native memory.
This is a conservative scheduling estimate, not a measured resident-memory guarantee; GPU memory is not queried.
Automatic capacity never falls below one client, preserving the existing serial baseline even when conservative reservations exceed free memory.
Unknown physical-memory support also falls back to one client; this minimum is not a guarantee that a host has enough RAM to run Minecraft.
Admission fails after five minutes without an admitted client completing, rather than limiting the total duration of a healthy queue.
Only an actual admitted client completion refreshes the stalled-wait deadline; unrelated Gradle tasks cannot keep a stuck queue alive.
Automatic clients default to a 1 GiB maximum Java heap; `-Pstrata.minecraftClientHeap=2g` or another positive JVM heap size changes both the JVM limit and its admission budget.
Use enough workers to make parallelism possible, for example `./gradlew check --max-workers=8`.
For a fixed correctness ceiling, use `-Pstrata.minecraftClientParallelism=4 --max-workers=6`; this explicitly bypasses resource adaptation.
Fixed parallel clients default to a 2 GiB heap; fixed serial execution retains the existing heap unless explicitly supplied.
Each client retains its own target's disposable run directory and image output; browser tests and web demonstration generation wait for selected clients in parallel mode.
Frame fences, pixel, input and resource assertions remain enabled.
Use one invocation and separate versioned targets rather than concurrent Gradle writers against the same checkout.

Performance collection is serial and runs without other builds or clients on the same machine.
`strata.performance.nativeOutput` disables automatic admission and forces serial execution without changing the existing performance heap.
Combining that property with an explicitly fixed parallel ceiling fails during configuration.
Passing correctness checks in parallel does not establish controlled performance evidence.

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
The planner creates one matrix job per selected Minecraft version in numeric order, with fail-fast disabled.
GitHub runs these jobs independently within the available runner concurrency, and a failed version can be retried separately.
Documentation and Dokka run in their own parallel job and upload the checked site.
Only the native showcase comparison and its CPU reference rendering stay with the Minecraft job producing that evidence; the documentation job does not repeat either check or start a game.
Common checks and Kover share one Gradle invocation so JVM tests run once.
API/core JavaScript tests and Web unit tests run once on Linux; Windows retains the platform-specific browser checks.

Gradle dependency caches may be saved and restored inside PRs, subject to GitHub's branch/ref isolation.
Independent Minecraft jobs read Gradle dependency caches but do not each save another large copy; the documentation and Qodana jobs retain full-model cache writers.
Loom project caches use the OS, selected projects, and build-model hash; successful misses save their regenerated inputs.
These caches contain dependencies and build intermediates, not test worlds, screenshots, parity receipts, or reports.
Acceptance evidence is generated for the selected revision.
A signed release's prepared artifacts are immutable publication inputs, as defined in [release publication](release.md).
Superseded JVM and Qodana runs on the same ref are cancelled.

## Qodana model

Qodana runs for PRs changing analyzed code or analysis configuration, manual full runs, and first release preparation.
It uses its recommended JVM profile without a baseline and receives every catalog-declared Java toolchain.
Each run analyzes the complete selected revision once with a zero-problem threshold; PR differential mode is disabled so it does not rebuild and reindex the base commit.
The workflow explicitly selects `qodana-jvm-community` in native mode so analysis can use the installed toolchains and restored Gradle user home.
One `--no-daemon` Gradle invocation compiles `classes` and `gametestClasses`, assembles the five plain common jars required by Loom's nested-library model, and generates the IDEA model.
Its JVM exits before analysis; compiled inputs remain available without assembling remapped distributions.
API/core use their `jvmJar` tasks, and multiplatform JVM modules expose common and JVM production/test roots with their real JVM classpaths.
Qodana's JVM model covers that JVM view; JavaScript-specific sources are checked by Detekt, the Kotlin/JS compiler, and browser tests.
Each versioned native integration links exactly one canonical component `TestSource` from the component benchmark corpus.
The verifier admits only that exact shared root for native consumers and retains the benchmark module's ordinary ownership; missing, duplicated, misclassified or broader roots fail verification.
The generated model co-locates common and JVM declarations without KMP source-set relationships, so `UnusedSymbol` can miss real calls across `expect`/`actual` declarations and typealiases.
The configuration lists only the affected bridge files for that inspection; other inspections and the zero failure threshold remain enabled.
Before extending that list, verify real callers and remove unused operations from every target; remove the exceptions when the analysis model can resolve those relationships.

Bootstrap disables configuration on demand and sets `strata.completeIdeaModel` plus `fabric.loom.ci`.
The latter preserves mapped binaries without optional source remapping.
The generated IDEA model assigns linked source roots, real compile/test/GameTest classpaths, and language levels to each owner; `rootJavaProjects` opens that model directly.
Bootstrap may replace its disposable `.idea`/`*.iml` outputs between revisions.
The workflow validates every discovered owner so an incomplete import cannot pass through exclusions.

Before analysis, disk reclamation runs only when free space is below 40 GiB; free space is logged again afterward.
IDE indexes are restored for matching build-model inputs and saved only after successful analysis and complete-model verification.
Keeping matrix Gradle caches read-only limits storage pressure so these indexes and the complete Loom project cache can coexist.
Every run recreates the project model and analysis reports and verifies every expected module.
Disable an inspection only with an actionable rationale in the checked-in configuration.

## Controller regression checks

`Workflow checks` runs actionlint, shell syntax checks, Python behavior tests, and Java/CI/Qodana model fixtures without starting Minecraft.
The linter step downloads official actionlint and ShellCheck release binaries, verifies their checksums, and installs the hash-checked Pyflakes wheel in a temporary environment.
Tool versions belong in the version catalog; review the pinned ShellCheck and Pyflakes digests in `.github/check-workflows.sh` when changing their versions.
Explicit executable paths and valid/invalid workflow, shell, and Python fixtures keep both script integrations enabled without Docker Hub credentials.
It compiles the isolated publication tools without loading product projects.
Behavior tests cover changed-path ownership, missing or altered prepared artifacts, successful preparation followed by a failed destination, immutable-release recovery, service conflicts, and receipt restoration.
Checks do not enforce workflow prose or source statement order.

## Distribution and documentation

[Release publication](release.md) owns first-time full validation, immutable preparation, per-destination retries, and moderation monitoring.
Documentation inputs trigger Pages separately after merge; the checked site is generated once and old rendered versions come from `gh-pages`.
See [Pages publication](release.md#pages-artifacts-and-deployment) and [documentation ownership](documentation.md#documentation-ownership).
Wait for CI using `gh run watch <run-id> --exit-status`; inspect logs after completion or failure.
