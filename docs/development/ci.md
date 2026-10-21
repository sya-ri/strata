# Continuous integration

CI distributes the checks from [local verification](build.md) and validates each analysis/publication boundary.
Use this document when changing workflow topology or caches; [release publication](release.md) owns external mutations.
CI scripts require Python 3.11 or newer for standard-library TOML parsing.
The Java setup action and Qodana bootstrap read their ordinary and frozen paired toolchains from the same catalog reader.

## Build configuration and concurrency

The typed Minecraft target matrix owns versions, toolchains, distributions, paired projects, and linked Dokka sources.
It derives task selection, artifact coordinates, sequencing, and runtime Java compatibility.
`verifyMinecraftFabricTargetMatrix` rejects missing owners.

Configuration on demand is disabled because the Kotlin/JS workspace and dependency lock require a complete project model.
Web-only checks use `-Pstrata.webOnly=true` to include the complete JavaScript workspace and its JVM parity dependencies without loading Minecraft adapters.
This scope accepts only fully qualified module `check` and `jsTest` tasks; publication and coverage aggregation use the complete build.
An invocation containing only `:ciMinecraftCheck` includes the Minecraft versions selected by `strata.minecraftVersions` and omits the documentation project.
Adding `:integration:docs:checkMinecraftShowcaseParity` retains that project for the native showcase comparison without loading other Minecraft versions.
The separate documentation job and development Pages generation include every runtime for Dokka and only the integration project supplying their assets.
Reader documentation checks use the same project scope, selecting the asset owner from the checked documentation build through the existing Minecraft planner.
All runtime sources remain available for the shared-source and Skill consistency checks.
Paired dedicated Fabric acceptance launches select their exact target from the requested integration task and invocation identity.
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

Heavy checks run on pull requests, with a manual full run available through `workflow_dispatch` with an empty `paired_manifest` input.
Merging a PR does not repeat JVM or Qodana verification.
The always-running planner and `CI result` job distinguish planned skips from failures, cancellation, and unexpected skipped checks.
Planning prepares Java and Gradle only when native-only changes need the configured shared-source ownership model; other inputs are selected with Python alone.

| Changed input | Selected work |
| --- | --- |
| Publication tools or workflows | Syntax, controller behavior fixtures, and isolated publication-tool compilation |
| Build-logic tests/resources | JVM tests and coverage, workflow fixtures, and native build-logic Qodana |
| Native build-logic analysis workflow or profile | Workflow fixtures and native build-logic Qodana |
| Private JVM Kotlin test or JMH sources, or the Qodana IDEA model | JVM tests and coverage, workflow fixtures, and both Qodana scans |
| Scoped JVM fixture collection verifier | JVM checks and coverage, workflow fixtures |
| Paired performance controller Python | Workflow behavior fixtures and compiled JVM/native boundary qualification |
| API/core/testkit JavaScript Kotlin sources or common Kotlin test sources | JVM checks and coverage, workflow fixtures, Web verification, and Qodana |
| Prose and links | Documentation and Skill consistency |
| Documentation generation, examples, images, or Dokka | Compiled documentation and generated-output checks |
| One Minecraft runtime or shared source | Every version using that source |
| API, common runtime, or build-wide settings | Common checks and all affected runtimes |
| Web | Linux unit/demo checks and Windows browser parity |
| Unrecognized input | Full verification |

Native helpers, native compiler inputs, mixed runtime changes and manual full CI keep their existing verification plans.
Private JVM test and JMH sources are consumed only by their owning JVM checks; shared verification helpers belong in their declared shared roots.
The shared benchmark fixture and workload roots retain their broader verification plans because the core, Web, and loaded-client tests also consume them.
JavaScript sources and common tests in the API, core, and performance testkit retain both JVM and Web verification, including the existing ABI and static checks.
They do not select loaded clients or full documentation; their owning tests still run through the existing job commands.
Resources and unrecognized source roots keep their broader plans, since fixtures such as fonts are also browser inputs.
Build-logic test-only changes and the IDEA analysis model do not select loaded clients, Web, documentation generation or compiled performance boundary qualification; production build logic and shared build settings still select full verification.
Private build-logic tests and their analysis configuration retain the standalone native scan without importing the unrelated product model.
Mixed product changes, manual full runs, and explicit analysis source references retain both scans.
The always-running Qodana result job requires each selected scan to succeed and each unselected scan to skip.

`writeCiSourceModel` exports the configured runtime/integration source roots from the existing Gradle target model and the documentation project's actual source roots.
Shared-source selection uses that output; deletions and both sides of renames participate in the diff.
Changes under a shared documentation source root also select documentation verification, including removed files; this conservatively includes files filtered out by that source set.
The planner creates one matrix job per selected Minecraft version in numeric order, with fail-fast disabled.
GitHub runs these jobs independently within the available runner concurrency, and a failed version can be retried separately.
Documentation and Dokka run in their own parallel job and upload the checked site.
Only the native showcase comparison and its CPU reference rendering stay with the Minecraft job producing that evidence; the documentation job does not repeat either check or start a game.
An explicitly empty Minecraft selection admits only the documentation asset owner when full documentation is requested.
Common checks and Kover share one Gradle invocation so JVM tests run once.
The complete compiled fixture route and its negative attempts run in two independent Windows jobs, both required by `CI result`.
The complete route keeps every paired sample on one host, and the Windows ownership-boundary checks run once with that route.
API/core JavaScript tests and Web unit tests run once on Linux; Windows retains the platform-specific browser checks.

Gradle dependency caches may be saved and restored inside PRs, subject to GitHub's branch/ref isolation.
Independent Minecraft jobs may restore Gradle dependency caches from other jobs, including the full-model writers on the default branch, but do not each save another large copy; the documentation and Qodana jobs retain full-model cache writers.
Loom project caches use the OS, selected projects, and build-model hash; successful misses save their regenerated inputs.
Full and reader-documentation jobs and development Pages builds use the same Loom ownership, input hash, and cache namespace.
Documentation builds include every versioned runtime, so full Qodana preparation may use their Loom cache as a fallback.
Gradle prepares missing integration inputs before analysis, and the complete cache is saved only after model and report verification succeeds.
These caches contain dependencies and build intermediates, not test worlds, screenshots, parity receipts, or reports.
Acceptance evidence is generated for the selected revision.
Each ordinary PR or full-CI Minecraft matrix job also runs `integration/fabric/run_acceptance.py` against a fresh loopback Fabric server using the production runtime and test Mod archives.
The paired client confirms server-owned input, visible revisions, and two independent connections; the server confirms both closures and clean shutdown.
Fresh logs and receipts are uploaded separately from build caches.

For loaded client verification alone, dispatch `jvm.yml` with `minecraft_versions` as comma-separated exact paired targets, `minecraft_backend` as `opengl` or `vulkan`, and `subject_ref` as the full lowercase public commit SHA in this repository.
The planner binds the selected target directories to the immutable subject.
Before client tasks execute, the driver's Gradle init script checks that the subject's execution graph includes both enabled client tasks with the requested backend and full-suite inputs.
Admission reads configured tasks rather than Kotlin script text, so build-script formatting and indirect task dependencies do not affect it.
This manual route disables configuration caching so admission runs for every invocation; ordinary CI commands retain their existing cache settings.
Both `runClientGameTest` and `runProductionClientGameTest` run with `strata.canvas.scope=full`, retaining their pixel, input, lifecycle, terminal, and resource gates.
The shared driver is independent of the subject revision, so an older open PR can be verified without copying workflow changes into it.
The loaded-client artifact includes source/tree identity, a source-archive digest, built JAR digests and native origins, complete parity outputs, and the Gradle log, including failures.
Vulkan jobs install Mesa's software driver and record actual device capabilities; these are correctness results for that environment and make no hardware GPU performance claim.
An unavailable backend or incomplete suite remains a failed gate.
Selected client verification cannot be combined with `paired_manifest`; leaving the client inputs at their defaults preserves manual full CI and ordinary PR selection.
A signed release's prepared artifacts are immutable publication inputs, as defined in [release publication](release.md).
Superseded JVM and Qodana runs on the same ref are cancelled.

## Qodana model

Qodana runs for PRs changing analyzed code or analysis configuration, manual full runs, and first release preparation.
It uses its recommended JVM profile without a baseline and receives every catalog-declared Java toolchain.
Each run analyzes the complete selected revision once with a zero-problem threshold; PR differential mode is disabled so it does not rebuild and reindex the base commit.
The workflow explicitly selects `qodana-jvm-community` in native mode so analysis can use the installed toolchains and restored Gradle user home.
The included `build-logic` build also receives a standalone native Gradle import, covering its production and test sources, resources, and linked game-independent pacing tests with their actual dependencies.
This scan lifts the recommended profile's default test-source exclusion while preserving test source roles and dependencies.
Its wrapper is generated with the selected source's checked-in root wrapper, so the analysis uses the build's Gradle distribution without another version pin.
The analysis controller supplies the inspection profile even when a selected historical revision predates that configuration; Java toolchains and imported sources still come from the selected product checkout.
The `native-build-logic-analysis` artifact retains the imported model, inspection logs, and SARIF from this independent zero-problem scan.
One `--no-daemon` Gradle invocation compiles `classes`, `gametestClasses`, and every discovered JMH owner's `jmhClasses`, assembles the five plain common jars required by Loom's nested-library model, and generates the IDEA model and declaration inventory.
Its JVM exits before analysis; compiled inputs remain available without assembling remapped distributions.
The declaration export validates each JVM owner's configured toolchain against all of its Kotlin and Java compilation tasks, and the analysis module binds to that compiler SDK independently of the bytecode target or analysis JVM.
The bootstrap registers the SDK's actual JRT modules in a disposable IDE configuration; verification checks the generated table, imported SDKs and each module binding against the fresh declarations.
The IDE may register its own SDK for source-free modules; every declared compiler SDK still requires one unchanged table entry and its original module bindings.
API/core use their `jvmJar` tasks, and multiplatform JVM modules expose common and JVM production/test roots with their real JVM classpaths.
Production common source sets retain separate analysis modules and their configured `dependsOn` relationships; JVM implementation and test roots remain in the owning JVM module.
Each common source-set fragment uses the owning JVM main compilation's platform, binary libraries and project dependencies, with the original compile/runtime scopes and no test dependencies.
Qodana's JVM model covers that JVM view; JavaScript-specific sources are checked by Detekt, the Kotlin/JS compiler, and browser tests.
Each versioned native integration links exactly one canonical component `TestSource` from the component benchmark corpus.
The verifier admits only that exact shared root for native consumers and retains the benchmark module's ordinary ownership; missing, duplicated, misclassified or broader roots fail verification.
Each module retains its actual compiler's selected inputs, dependencies and Kotlin identity.
Shared or filtered source roots use byte-identical per-module views under `qodana-analysis-sources/`, while the declaration inventory retains the authoritative original paths and hashes.
Each execution recreates that owned directory, copies exactly the compiler-selected files and records the current revision in `build/qodana/source-views.json`.
These analysis inputs are never published or committed and must remain outside Git and IDEA exclusions; Qodana's recommended profile ignores Git-ignored paths even when they remain registered as source roots.
Completeness verification rejects hidden inputs, stale provenance, changed bytes, missing or extra files, collapsed contexts and tracked generated views.
Source and compiled-library roots use resolved file URLs without directory suffixes, and declared output directories that were never produced are omitted from libraries.
A module's compiled outputs remain acceptance evidence and are omitted from its own library entries.
No synthetic fallback library or cross-variant friend relationship substitutes for a compiler's own sources.
Analysis failures and error notifications fail completeness verification even when the inspection finding count is zero.
Every SARIF run must identify the selected revision and explicitly record successful analysis invocations.
The verifier also requires resolved Kotlin source owners and recorded Kotlin inspection work; matching exported metadata alone is insufficient.
Kotlin facets retain the owning JVM compilation's module name, explicit API mode and bytecode target; common fragments identify their source set and declared refinement edges, while the owning JVM module retains its test friend paths.
`UnusedSymbol` and `RedundantVisibilityModifier` remain enabled without path exclusions; Mixin annotations identify framework entry points.

Bootstrap disables configuration on demand and sets `strata.completeIdeaModel` plus `fabric.loom.ci`.
The latter preserves mapped binaries without optional source remapping.
The generated IDEA model assigns linked source roots, real compile/test/GameTest classpaths, and language levels to each owner; `rootJavaProjects` opens that model directly.
Bootstrap may replace its disposable `.idea`/`*.iml` outputs between revisions.
Module registration and project SDK validation read XML structure rather than attribute order or line layout.
The workflow validates every discovered owner so an incomplete import cannot pass through exclusions.
The shared JVM model exports configured auxiliary Java/Kotlin inputs and resources as `TestSource`/`TestResource` in their actual owner, including nondefault directories, and retains their real compile/runtime configurations in IDEA's TEST scope.
Roots already admitted by production keep that ownership once.
This IDE view is an analysis union; isolated Gradle compilations and `checkStrataSkillExampleClasspath` still enforce API-only skill dependencies.
`qodanaDeclarationInventory` discovers owners from the configured Gradle plugin/source-set model and records actual module identities, Java requirements, source/resource declarations, file membership and hashes, and resolved project/library dependency identities independently of IML and Qodana JSON.
After compilation finishes, the inventory shares directory membership and fingerprints only within its current task action; each execution reads them afresh.
Each JVM project resolves its compiler and runtime configurations in its own export task, and the root inventory consumes those revision-bound outputs without resolving another project's configurations.
The same inventory depends on working auxiliary compiler outputs, records their actual bytes, and rejects missing or changed outputs before accepting their generated/imported model roots.
Nonexistent directories remain explicit declarations without becoming model roots; compiler and harness outputs are build intermediates, and authored JMH roots inside excluded build directories fail verification.
The verifier compares the declaration inventory with both generated IML and imported Qodana roots, SDKs, language levels, and dependency closures.
Production JVM compilations retain separate compile/runtime closures; a dependency present only in the TEST scope cannot satisfy either production closure.
The selected Git revision, clean tracked bytes, authored membership, and SARIF revision must agree before declaration completeness can pass.
Missing owners or roots, paired omission from both models, wrong kinds or owners, duplicate roots or dependencies, broader paths, changed source membership, and unresolved required libraries fail verification.
Native consumers retain their exact canonical component `TestSource` links.
Other shared roots, including authored showcase inputs, are accepted only when the foreign owner independently declares the same exact root and kind in the configured project inventory.

Before analysis, disk reclamation runs only when free space is below 40 GiB; free space is logged again afterward.
IDE indexes are restored for matching build-model inputs and saved only after successful analysis and complete-model verification.
Their identity follows Gradle configuration, wrapper and catalog inputs, model-generation scripts, and the analysis profile and workflow; other CI scripts and Python regression tests do not invalidate compatible indexes.
Keeping matrix Gradle caches read-only limits storage pressure so these indexes and the complete Loom project cache can coexist.
Every run recreates the project model and analysis reports and verifies every expected module.
The `qodana-model-evidence` artifact retains the fresh declaration inventory, generated IML/project metadata, and bootstrap task log in their repository-relative paths.
The `qodana-report` artifact retains the imported model, inspection logs, and SARIF.
Both uploads run after failed analysis so available evidence remains inspectable.
Gradle task outcomes distinguish executed work from `NO-SOURCE`, `SKIPPED`, `UP-TO-DATE`, and `FROM-CACHE`; build-cache reuse does not establish current source membership or analysis.
Cold and reused-index acceptance must retain equivalent revision-bound owner/root/dependency inventories and fresh reports; zero findings, compilation, or Detekt cannot establish analysis of an omitted JMH corpus.
Disable an inspection only with an actionable rationale in the checked-in configuration.

## Controller regression checks

`Workflow checks` runs actionlint, shell syntax checks, Python behavior tests, and Java/CI/Qodana model fixtures without starting Minecraft.
Declare external production files consumed by TestKit fixtures as inputs of `build-logic:test` so source changes invalidate cached fixture results.
The linter step uses the official mise action to install the CLI tools declared in `.github/mise.toml` from `.github/mise.lock`, including the locked Pyflakes package graph.
Run `mise -C .github install --locked` and `mise -C .github exec -- bash check-workflows.sh` locally; tool updates regenerate the lockfile rather than changing bootstrap code or embedded checksums.
Commit both the lockfile and `.github/.mise/locks/` package graphs; regenerate them with `mise -C .github lock --platform linux-x64,windows-x64` when changing tools.
Gradle dependencies and plugins remain in the version catalog.
Explicit executable paths and valid/invalid workflow, shell, and Python fixtures keep both script integrations enabled without Docker Hub credentials.
It compiles the isolated publication tools without loading product projects.
Behavior tests cover changed-path ownership, missing or altered prepared artifacts, successful preparation followed by a failed destination, immutable-release recovery, service conflicts, and receipt restoration.
Checks do not enforce workflow prose or source statement order.

## Distribution and documentation

[Release publication](release.md) owns first-time full validation, immutable preparation, per-destination retries, and moderation monitoring.
Documentation inputs trigger Pages separately after merge; the checked site is generated once and old rendered versions come from `gh-pages`.
See [Pages publication](release.md#pages-artifacts-and-deployment) and [documentation ownership](documentation.md#documentation-ownership).
Wait for CI using `gh run watch <run-id> --exit-status`; inspect logs after completion or failure.

The JVM workflow also supports an explicit paired measurement manifest; ordinary PRs without a `Performance-Inputs` declaration skip its Windows job.
See [paired JVM CI](performance-testkit.md#opt-in-paired-jvm-ci) for frozen inputs, supported collectors, process bounds and complete-attempt acceptance.
A nonempty manual `paired_manifest` must pass the existing immutable producer/consumer selection and exactly match the `Performance-Inputs` trailer before the planner emits any outputs.
This explicit dispatch selects the complete paired job and independent compiled fixture qualification without repeating ordinary JVM, Web, documentation or Minecraft verification.
The paired job retains its native boundary controls, full frozen collection and attempt archives.
Empty-input manual runs and ordinary PR planning keep their existing verification scope, including when the head declares performance inputs.
Measurement and Source preparation do not supply final-revision ordinary quality evidence for either the prepared subject or the shared driver; those revisions retain their independent required checks.
