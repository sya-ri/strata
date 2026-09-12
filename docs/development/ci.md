# Continuous integration

CI distributes the same module checks used in [local verification](../development/build.md), then validates analysis and publication inputs at their owning boundaries.
This document records workflow structure, build-model ownership, concurrency, and cache policy.
The [release controller](release.md) owns external publication and provenance checks; [documentation maintenance](documentation.md) owns generated reader content and API-site staging.

## Build configuration and concurrency

The root build owns one typed Minecraft target matrix containing each exact version, Java toolchain, distribution kind, runtime and integration project paths, and linked Dokka source ownership.
That matrix derives aggregate documentation dependencies, loaded-client sequencing, remap sequencing, publishable runtime selection, artifact coordinates, and per-project toolchains; `verifyMinecraftFabricTargetMatrix` rejects an included version project or linked source boundary that is missing from the matrix.
Loom's runtime Java compatibility property follows the same typed toolchain, so development clients select native libraries for their own Java release rather than the Gradle daemon's Java release.
Configuration on demand is enabled so a targeted API, core, headless, documentation-helper, or benchmark task does not configure the complete Loom project inventory.
Each targeted integration project explicitly evaluates its paired runtime project before reading that runtime's compiled source-set output, so an isolated integration task retains the exact Loom-provided Minecraft classpath under configuration on demand.
Documentation launchers inherit common compile and JAR dependencies from their runtime classpath rather than forcing redundant cross-project `classes` task paths.
Full `check`, publication, Qodana, and loaded-game commands still select and configure every required target through their real project and task dependencies.
Minecraft client verification associates every selected Loom asset preparation task and client launch with one Gradle shared build service whose single usage permit prevents races on Loom's mutable asset cache and the native client environment without coupling a targeted task to every other version project.
It also orders the selected asset tasks before the selected clients so Gradle can validate their intentionally shared output directory while configuration on demand leaves unselected versions untouched.
Every development, production, and published-coordinate client verification task seeds its own disposable run directory after cleanup and before the JVM launches: initial accessibility onboarding and gameplay tutorials are disabled, narration is off, and master sound volume is zero.
Vanilla tutorial toasts can otherwise cover the screenshot's pixel-oracle panels even after the frame readiness fence completes; the test-owned `tutorialStep:none` option removes that unrelated overlay while retaining the exact rendering assertions.
The shared setup preserves unrelated test options and rejects paths outside the owning project's build directory; ordinary `runClient` launches and personal Minecraft settings are unchanged.
The Canvas/Slot pixel oracle temporarily hides the native HUD for its complete screenshot scene, restoring the previous state even on failure.
This also suppresses tutorial and recipe toasts, including recipe notifications arriving after the server-seeded inventory synchronization; clearing an existing toast queue once would not cover that race.
The actual Strata screen, native item rendering, frame fence, and every pixel assertion remain enabled.
The official-mapping `remapJar` tasks use a second single-permit build service because each concurrent remapper retains a complete mapped game graph and can exhaust a hosted CI runner's heap.

## JVM shards and reusable inputs

The JVM workflow discovers every paired versioned runtime and integration project, sorts their numeric Minecraft versions, and deals successive versions across generated bounded shards on separate hosted runners with fail-fast disabled.
This spreads older, slower release families across runners instead of grouping them together; each shard still preserves the in-build client and remap limits above.
The planner reads the explicit native integration input from the documentation build and assigns documentation checks exactly once to the shard containing that target, even when it is not the newest supported version.
Missing, ambiguous, or unpaired documentation inputs fail planning rather than launching an extra client outside the selected shard.
The complete Loom inventory remains numerically ordered independently of shard assignment, and each shard's displayed name lists its actual versions.
Common checks include the CPU font backend and its isolated dependency and font-capability workers without launching Minecraft.
Workflow syntax and the release, Java-inventory, and CI-model shell regressions run in an independent `Workflow checks` job alongside Gradle checks.
That job retains the complete Git history and catalog-selected Java toolchains because release fixtures invoke Gradle in temporary checkouts.
It restores a compatible Gradle cache read-only; common checks and coverage keep their existing matrix identities for cache reuse.
The representative integration checks own their matching native-to-offline font comparisons, so full `check` and the existing Minecraft shards run those gates without adding loaded clients to the common shard.
It runs only when code, build inputs, its own workflow, the compiled README contract, or generated showcase evidence changes; canonical prose that cannot affect those gates does not launch loaded clients.
Gradle's enhanced user-home cache uses strict job matching for common and Minecraft shards so one writer cannot restore and resave state from another matrix entry.
Read-only workflow checks, coverage, Qodana, and Documentation jobs deliberately accept the newest compatible Linux job cache and never write it back, avoiding a cold dependency fan-out and upstream rate limits without sharing Loom state.
Requiring a strict own-job match in a job that never writes a cache would prevent these readers from using the successful common and Minecraft writers.
It is writable only from successful `master` runs of common checks and generated Minecraft shards because each produces distinct reusable outputs; pull requests, workflow checks, coverage, Qodana, and Documentation restore it read-only to avoid redundant, evidence-only, or branch-scoped entries.
Every hosted job excludes Loom state from the enhanced Gradle user-home cache.
Each Minecraft shard separately restores its project-local Loom repository with an OS-, shard-, and build-model-derived immutable key.
The model hash includes the catalog, wrapper, Gradle properties, root build and settings, and only the versioned runtime and integration build scripts selected by that shard, so changing one release family does not evict every unrelated family.
Restoration accepts only that exact complete model hash; a miss regenerates the Loom repository from authoritative inputs, and a replacement is saved only after a successful `master` cache miss.
Minecraft assets remain ordinary upstream inputs fetched by the loaded-client tasks instead of being copied into per-shard multi-gigabyte job caches that evict the smaller build-model and dependency entries.
Qodana hashes the complete discovered Loom project inventory and first restores its exact all-project Loom cache, falling back to the newest Minecraft cache as a warm starting point on a miss.
Missing content-addressed entries are reproduced from authoritative inputs and are never accepted as analysis evidence.
After successful analysis, model verification, and report upload on `master`, Qodana saves only `.gradle/loom-cache` under that complete model key when the exact entry was absent.
This gives subsequent analysis jobs the complete dependency model instead of repeatedly rebuilding the versions absent from a single Minecraft shard; pull requests only restore it, and neither the Gradle user home nor IDE or analysis outputs become additional cache writers.
Loaded-client worlds, screenshots, parity receipts, release documentation, test reports, coverage, and Qodana results are never accepted from the build cache; protected release invocations and the master-owned Pages reconstruction of immutable tags use `--no-build-cache` so mandatory release evidence is recreated and validated on the selected revision.
Gradle configuration-cache diagnostics accept and reuse the targeted common `runtime:minecraft` check, but a versioned Loom `classes` invocation currently rejects its `ProcessResources` action because the per-version metadata expansion captures the Gradle `Project` object.
It is therefore not enabled globally: every hosted shard currently uses one Gradle invocation, so persisting that project-local cache would add transfer cost without avoiding any loaded client, remap, or analysis work, and the versioned resource boundary must become configuration-cache-compatible before this decision is reopened.
Superseded JVM and Qodana workflow runs on the same ref are cancelled so rapid pushes do not keep obsolete clients or analysis running.

## Qodana model

Qodana runs its recommended JVM inspection profile in CI without a baseline.
The workflow makes every Java toolchain declared by the version catalog available to the host-side native Qodana process so it can resolve each module model and its dependencies.
It explicitly selects the `qodana-jvm-community` linter and native mode on the CLI; the legacy image-valued YAML linter otherwise selects Docker, isolating analysis from the installed toolchains and restored Gradle user home.
It restores the Gradle user home read-only, compiles every `classes` and `gametestClasses` boundary, and assembles the five plain common jars referenced by Loom's nested-library model before inspection without assembling remapped distribution jars.
Qodana's bootstrap compiles those inputs and generates the IDEA model in one Gradle invocation, with configuration on demand disabled, the analysis-only `strata.completeIdeaModel` project property, and Loom's official `fabric.loom.ci` system property.
The invocation uses `--no-daemon` so its Gradle JVM exits before inspection without a second project configuration or separate daemon-stop step.
The CI property keeps mapped binary dependencies in the IDEA modules while preventing Loom from downloading and remapping optional dependency source artifacts.
The analysis-only property generates the official Gradle IDEA project and augments each versioned module with the real compile classpath and, for integration projects, the real test and GameTest classpaths and source roots.
This preserves one canonical physical copy of compatible mapped sources while preventing IntelliJ from assigning linked roots to dependency-free directory modules.
The explicitly authorized bootstrap may replace its generated model while Qodana moves between pull-request revisions, and exposes that graph through disposable `.idea/modules.xml`, `*.iml`, and project-SDK metadata that binds inherited analysis to Qodana's registered JBR while retaining each module's Java language level.
The `rootJavaProjects` setting opens that model directly instead of asking Qodana to reconstruct a different Gradle model.
The workflow then checks the emitted project structure against every discovered versioned runtime and integration owner, including their SDK, dependency, runtime-source, and GameTest-source boundaries, so a partial project import cannot pass only because inspections were excluded.
The much larger Qodana IDE cache is not persisted and is removed after the run to preserve hosted-runner disk space; it may be enabled only after the complete-model import is green and its key covers every IDE-model input, while required analysis inputs remain ordinary reproducible build outputs rather than cache-only state.
Before analysis, the workflow measures free disk space and runs the existing reclamation action only below 40 GiB.
That budget leaves room for dependency restoration, the complete compiled model, and the disposable IDE cache without uninstalling unrelated runner packages on every run; free space is logged again after analysis.
Static-analysis rules are enabled when they produce actionable improvements; rules that systematically make code less clear are disabled with a durable rationale in the checked-in configuration.

## Controller regression checks

The independent workflow-check job runs the pinned official actionlint container, parses every tracked release shell script with `bash -n`, and runs isolated regressions for tag replacement, ruleset drift and response normalization, release and controller Pages run/artifact/deployment binding, safe immutable-subtree comparison, archive receipt drift, global deployment ordering, CDN age handling, and bounded public polling alongside the Gradle gates.

Run `./gradlew :quality:benchmarks:jmh` for the temporary JSON report and follow the methodology and acceptance gates in [Rendering performance](../development/performance.md).

## Documentation ownership

Pages first freezes the exact master controller and release identities in a small read-only source job.
The controller-site and independent immutable-release producers then run concurrently on separate runners, each revalidating those identities before executing a local action or build.
Deployment requires both successful producers and preserves their exact artifact identities, byte-equivalence checks, and final current-master verification.
The independent producer still regenerates the tagged source without the build cache; parallelism does not make one producer's output evidence for the other.

Keep canonical API and runtime contracts, reader guides, release notes and publication bodies, compiled examples, and deterministic generated images and receipts in Git.
Update generator sources and regenerate checked outputs instead of editing generated documents by hand.
Keep task plans, working notes, status reports, and unfinished drafts under the ignored `build/` directory; promote durable decisions into the relevant canonical guide when the work is complete.
Do not retain release-incident timelines or external-service status snapshots in reader guides; encode any required recovery boundary in executable contracts and tests.
Loaded worlds, transient screenshots, raw benchmark output, and test or coverage reports remain untracked build outputs.

## Evidence

Dependency and tool-derived intermediates may be cached under an explicit complete input identity.
Loaded worlds, screenshots, parity receipts, generated documentation, and analysis or coverage reports remain current-revision evidence and are recreated.
Required release and immutable-tag reconstruction paths disable build-cache reuse for that evidence.
