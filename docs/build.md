# Build and release

The checked-in wrapper and version catalog are the source of truth for build-tool and dependency versions.
Common modules target the baseline Java toolchain; version-specific Minecraft modules target the toolchain required by their game version.
The `runtime:minecraft-fabric-26.2` and `runtime:minecraft-fabric-26.1` modules target Java 25, use the version-catalog Fabric Loom plugin in no-remap mode for their unobfuscated clients, and package the `api`, `runtime:core`, `runtime:headless`, `runtime:minecraft`, and `runtime:minecraft-fonts-lwjgl` jars under `META-INF/jars` exactly once.
Their Fabric metadata declares an exact Minecraft requirement and catalog-derived Loader and Fabric Language Kotlin lower bounds; neither runtime uses Fabric API.
The `runtime:minecraft-fabric-1.21.11` through `runtime:minecraft-fabric-1.20.5` modules target Java 21, compile against official Mojang mappings, remap their distribution jars through the version-catalog Fabric Loom remap plugin, and package the same common jars exactly once.
The `runtime:minecraft-fabric-1.20.4` through `runtime:minecraft-fabric-1.20` modules apply the same publication and packaging contract with the Java 17 toolchain required by those releases.
Every versioned Fabric runtime module declares an exact Minecraft requirement and catalog-derived Loader, Fabric Language Kotlin, and Java lower bounds; none uses Fabric API at runtime.
Remapped runtimes declare Loader through Loom's `modCompileOnly` configuration so their own installer metadata and Mixin libraries are resolved even when no integration project is configured.
The unobfuscated 26.x runtimes use `compileOnly`, which their Loom dependency pipeline processes directly.
The shared artifact verifier requires exact catalog-derived Loader and Mixin identities in the final JAR manifest, rejecting missing, unresolved, or mismatched build metadata without rewriting the archive; every Maven publication runs the artifact and publication-metadata verifiers before uploading or installing files.
The 26.x projects compile the complete neutral `runtime/minecraft-fabric-shared`, `runtime/minecraft-fabric-identifier`, and `runtime/minecraft-fabric-unobfuscated` source trees and add only their version-specific current-screen bridge and metadata.
The 1.21.11 project combines the cross-version and identifier-alias roots with the remapped 1.21 adapter sources and the record-input release-family root.
The 1.21.10 and 1.21.9 projects combine the same cross-version, remapped 1.21, and record-input roots but keep their `ResourceLocation` aliases in each versioned module because that mapped name is not shared by every consumer.
The 1.21.8 through 1.21.4 projects combine the cross-version and remapped 1.21 roots with the complete primitive-input release-family root, while 1.21.3 through 1.21 reuse its compatible Kotlin screen root and the complete Java bridge root for the direct player-skin result.
The 1.21.5 through 1.21.2 projects add the render-type and pose-stack bridge root, 1.21.1 and 1.21 supply their older direct texture blitter locally, and 1.21.6 through 1.21.11 add the render-pipeline and stratum bridge root.
The 1.20.6 and 1.20.5 projects reuse the compiler-proven 1.21.1 capability roots but own their older constructor-based `ResourceLocation` factories and parsers together with their exact native texture bridges.
The 1.20.4 through 1.20.2 projects keep the compatible complete roots and share the pre-`ResolvableProfile` skin and key-binding bridges; their profiles select active legacy GUI sprites and reproduce the code-defined scrollbar track without version-string dispatch, including 1.20.2's header separator capability.
The 1.20 and 1.20.1 projects keep only their proven complete shared roots and own exact artifacts and ABIs around the shared earlier atlas extraction, Authlib 4 skin bridge, and client-runner APIs; their portable profile records exact Vanilla atlas borders so the common renderer reproduces those nine-slices without recognizing a game version.
The shared frame presenter calls compile-time dynamic-texture and native-pixel bridges supplied by the 1.21 through 1.21.5 version projects, the 1.21.6 release-family root, or the unobfuscated release-family root, preserving ABGR-versus-ARGB access and unnamed-versus-named texture construction without reflection.
Code enters the legacy source root only after every consuming target compiles it and passes both development and production-jar loaded-client verification.
Every source directory is linked as a whole root because Gradle file-tree include filters are not a reliable IDE or static-analysis ownership boundary.
Consumers select exactly one versioned runtime artifact because the adapters deliberately expose the same packages and public class names.
The root build owns one typed Minecraft target matrix containing each exact version, Java toolchain, distribution kind, runtime and integration project paths, and linked Dokka source ownership.
That matrix derives aggregate documentation dependencies, loaded-client sequencing, remap sequencing, publishable runtime selection, artifact coordinates, and per-project toolchains; `verifyMinecraftFabricTargetMatrix` rejects an included version project or linked source boundary that is missing from the matrix.
Loom's runtime Java compatibility property follows the same typed toolchain, so development clients select native libraries for their own Java release rather than the Gradle daemon's Java release.
Configuration on demand is enabled so a targeted API, core, headless, documentation-helper, or benchmark task does not configure the complete Loom project inventory.
Each targeted integration project explicitly evaluates its paired runtime project before reading that runtime's compiled source-set output, so an isolated integration task retains the exact Loom-provided Minecraft classpath under configuration on demand.
Documentation launchers inherit common compile and JAR dependencies from their runtime classpath rather than forcing redundant cross-project `classes` task paths.
Full `check`, publication, Qodana, and loaded-game commands still select and configure every required target through their real project and task dependencies.
Minecraft client verification associates every selected Loom asset preparation task and client launch with one Gradle shared build service whose single usage permit prevents races on Loom's mutable asset cache and the native client environment without coupling a targeted task to every other version project.
It also orders the selected asset tasks before the selected clients so Gradle can validate their intentionally shared output directory while configuration on demand leaves unselected versions untouched.
Every development, production, and published-coordinate client verification task seeds its own disposable run directory after cleanup and before the JVM launches: initial accessibility onboarding is disabled, narration is off, and master sound volume is zero.
The shared setup preserves unrelated test options and rejects paths outside the owning project's build directory; ordinary `runClient` launches and personal Minecraft settings are unchanged.
The official-mapping `remapJar` tasks use a second single-permit build service because each concurrent remapper retains a complete mapped game graph and can exhaust a hosted CI runner's heap.
The JVM workflow discovers every paired versioned runtime and integration project, sorts their numeric Minecraft versions, and distributes them across generated bounded shards on separate hosted runners with fail-fast disabled; the final shard also checks the generated documentation, while every shard preserves the in-build client and remap limits above.
Common checks include the CPU font backend and its isolated dependency and font-capability workers without launching Minecraft.
The representative integration checks own their matching native-to-offline font comparisons, so full `check` and the existing Minecraft shards run those gates without adding loaded clients to the common shard.
It runs only when code, build inputs, its own workflow, the compiled README contract, or generated showcase evidence changes; canonical prose that cannot affect those gates does not launch loaded clients.
Gradle's enhanced user-home cache uses strict job matching for common and Minecraft shards so one writer cannot restore and resave state from another matrix entry.
The read-only coverage shard deliberately accepts the newest compatible Linux job cache and never writes it back, avoiding a cold dependency fan-out and upstream rate limits without sharing Loom state.
It is writable only from successful `master` runs of common checks and generated Minecraft shards because each produces distinct reusable outputs; pull requests, coverage, Qodana, and Documentation restore it read-only to avoid redundant, evidence-only, or branch-scoped entries.
Every hosted job excludes Loom state from the enhanced Gradle user-home cache.
Each Minecraft shard separately restores its project-local Loom repository with an OS-, shard-, and build-model-derived immutable key.
The model hash includes the catalog, wrapper, Gradle properties, root build and settings, and only the versioned runtime and integration build scripts selected by that shard, so changing one release family does not evict every unrelated family.
Restoration accepts only that exact complete model hash; a miss regenerates the Loom repository from authoritative inputs, and a replacement is saved only after a successful `master` cache miss.
Minecraft assets remain ordinary upstream inputs fetched by the loaded-client tasks instead of being copied into per-shard multi-gigabyte job caches that evict the smaller build-model and dependency entries.
Qodana hashes the complete discovered Loom project inventory and restores the newest compatible Minecraft cache read-only as a warm starting point before compiling and importing the complete model; missing content-addressed entries are reproduced from authoritative inputs and are never accepted as analysis evidence.
Loaded-client worlds, screenshots, parity receipts, release documentation, test reports, coverage, and Qodana results are never accepted from the build cache; protected release invocations and the master-owned Pages reconstruction of immutable tags use `--no-build-cache` so mandatory release evidence is recreated and validated on the selected revision.
Gradle configuration-cache diagnostics accept and reuse the targeted common `runtime:minecraft` check, but a versioned Loom `classes` invocation currently rejects its `ProcessResources` action because the per-version metadata expansion captures the Gradle `Project` object.
It is therefore not enabled globally: every hosted shard currently uses one Gradle invocation, so persisting that project-local cache would add transfer cost without avoiding any loaded client, remap, or analysis work, and the versioned resource boundary must become configuration-cache-compatible before this decision is reopened.
Superseded JVM and Qodana workflow runs on the same ref are cancelled so rapid pushes do not keep obsolete clients or analysis running.
First-attempt Documentation runs use a `pages-controller` concurrency group that no historical workflow definition shares, while each rerun uses a run-ID-qualified group that cannot replace a pending first attempt.
A newer master push cancels the obsolete first attempt, while a rerun cannot cancel that work and may proceed only while its frozen commit remains the exact `origin/master` head.
Every deployment reconstructs each existing release tag under its immutable `/releases/{version}/` subtree so a later deployment cannot replace release evidence.

Every Kotlin compilation uses explicit API mode and treats warnings as errors.
Canvas GUI-consumption hooks compile against the Mixin and MixinExtras versions already supplied by the configured Fabric Loader.
Those dependencies are compile-only, remain in the version catalog, and add neither a nested runtime library nor a Fabric rendering API requirement.
The exact Loader dependency versions are checked against [its primary build configuration](https://github.com/FabricMC/fabric-loader) and the [MixinExtras setup contract](https://github.com/LlamaLad7/MixinExtras#setup).
Detekt and Kotlinter are applied to all project modules.
The Canvas family roots and loaded-test roots are explicitly included in Detekt's source inputs, because [its generic task](https://detekt.dev/docs/gettingstarted/gradle/#available-plugin-tasks) otherwise scans only conventional project directories.
Kover exposes HTML and XML reports without enforcing a coverage threshold.
Run aggregate coverage with `./gradlew :koverHtmlReport :koverXmlReport -Pkover`; each fully qualified root report task first runs the ordinary JVM test suite selected by `koverJvmTests` without discovering same-named tasks in unrelated projects.
`gradle/kover-jvm-projects.txt` is the single source of truth for that aggregation boundary: it includes modules with ordinary JVM tests and excludes testless remapped adapters, loaded-client-only integrations, and benchmarks so coverage does not configure or compile unrelated Loom projects.

Each publishable JVM module has a Maven publication with source and Javadoc artifacts, MIT license metadata, SCM metadata, and an optional in-memory signing setup.
Provide `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, optional `signingInMemoryKeyId`, and optional `signingInMemoryKeyPassword` Gradle properties when publishing to Maven Central.
Environment variables use the `ORG_GRADLE_PROJECT_` prefix followed by the same property name.
The release workflow definition is controlled by the exact current `origin/master` commit, while the product source is the separately signed and protected release-tag commit.
Its first job has no protected environment or write-capable token, requires the tag commit to be an ancestor of that controller, reads the release version from the tag tree, and requires successful JVM and Qodana runs independently for both commits.
It requires one successful push-triggered Documentation run for the exact current controller commit and retrieves that run's separate unexpired controller and release-evidence artifacts, verifying both downloaded ZIP sizes and SHA-256 digests against immutable Actions metadata.
Each producer gives its artifact a run-ID-, job-, and producer-attempt-qualified name, freezes its exact artifact ID and digest as a job output, and lets a deploy-only rerun reuse those upstream outputs instead of guessing from the overall run attempt; the deploy job revalidates those identities through the API, downloads both exact IDs, and gives the exact controller name to the Pages deployer.
The post-deployment verifier reads every all-attempt job and artifact API page, rejects incomplete counts, duplicate IDs, invalid creation timestamps, unexpected names, or foreign run and commit bindings, selects exactly one artifact inside each latest successful producer's logical execution window, and binds that artifact's name-suffix attempt back to the matching all-jobs history while requiring the successful deploy job to belong to the overall run attempt.
The sealed tag-workflow compatibility path retains its historical fixed artifact name but requires exactly one artifact inside the latest successful build job's execution window; the subsequent receipt and byte-equivalence checks prevent any selected rerun pair from being mixed or tampered.
The release evidence artifact's `/releases/{version}/` subtree must carry the exact tag receipt, while the controller artifact root must carry its exact `master` receipt and the matching immutable subtree must remain pinned to the tag.
The read-only release-evidence job freezes the exact master-owned staging-tool blob identities, checks out and independently regenerates the exact current tag without the build cache, and only then materializes the tools from those controller blobs as read-only files that it revalidates before and after rebuilding every canonical immutable subtree.
The verifier safely validates and traverses only regular files and directories from both immutable subtrees, then compares every regular-file path, size, and SHA-256 digest; empty directories are transport-neutral because archive and ZIP transports may omit them, while any file below one still participates in the complete comparison.
For predecessor verification, the target identity selects the predecessor subtrees while a separate evidence-root identity remains bound to the current tag; the comparator checks the current root against both current subtrees, the predecessor subtrees against each other, and the complete immutable release inventory.
After fetching every API page and sorting validated timestamps with numeric IDs as deterministic tie-breakers, it requires the newest deployment in either Pages environment to target the exact current controller commit through `github-pages-controller` and the newest status to be successful and linked to that same Documentation run.
The global deployment query is not filtered by environment; after ignoring unrelated environments, a newer deployment through the retired Pages environment cannot be hidden.
The active `github-pages-controller` environment must disable administrator bypass and use custom deployment policies containing exactly one branch rule for `master`.
The retired `github-pages` environment must also disable administrator bypass and use custom deployment policies with an empty branch-and-tag rule set, so rerunning an older workflow definition cannot enter either Pages deployment environment.
The public receipts are supporting propagation evidence rather than the authoritative source binding: GitHub Pages' Fastly layer can retain a response for 600 seconds despite request cache directives or unique queries, so the workflow independently polls the root `master` receipt and immutable release receipt for up to 900 seconds and accepts each matching response only when its final HTTP response has no `Age` header or an age of at most five seconds.
It then repeats the complete Actions artifact and deployment verification and requires the six-field compatibility record to remain identical; in ordinary forward verification its release and controller run IDs and deployment IDs are equal because both artifacts and the only deployment belong to one master run.
Sealed historical final-verification workflows may instead supply their exact tag-owned Documentation run together with the current master controller run.
That compatibility path binds the historical run, jobs, unexpired `github-pages` artifact, deployment, and successful status to the requested tag and commit; materializes the missing comparator from its exact regular blob in the controller commit; and requires both the historical artifact root and its immutable release subtree to match the independently regenerated target subtree byte for byte.
Only then does the protected write-enabled job check out the verified forty-character tag commit directly at the workspace root, confirm that its `HEAD` is the product source, and use an exact controller-commit guard to load the wildcard ruleset verifier, contract, audited receipt, Pages verifier, and public-receipt waiter as regular Git blobs from fixed paths in the already verified controller commit.
The guard disables Git replacement objects, requires each controller tool's declared regular-blob mode (`100644` or `100755`), validates every materialized blob hash and syntax, makes the temporary bundle read-only, and revalidates the complete bundle immediately before every later ruleset or Pages use.
The bundle is removed by an always-running terminal step whose target is bound to the initialization step output and restricted to the job-specific runner-temporary prefix.
Those controller-owned tools repeat the controller-master, signed tag-object, ruleset, environment policy, both Actions artifacts, single active deployment, immutable-subtree comparison, and public receipt checks after environment approval.
Every protected Gradle invocation receives both `strata.sourceRevision` and `strata.sourceCommit`, so the controller commit in Actions' `GITHUB_SHA` cannot enter product documentation, manifests, or source receipts; the Actions summary records the controller commit, product-source commit, tag object, and Pages evidence separately.
Some signed historical releases predate current external-service contracts.
The forward controller may bridge those boundaries only through narrowly scoped compatibility inputs whose controller revision, allowed source paths, Git modes, byte identities, and permitted operations are fixed by checked contracts and tests.
Compatibility tools operate on isolated evidence, restore temporary state on every exit, and cannot turn partial or conflicting public content into a writable retry.
Historical public artifacts remain immutable: service verification compares remote metadata and bytes with locally rebuilt tag evidence and fails closed instead of deleting, replacing, or overwriting content.
Repeated remote ruleset, tag, controller, and source comparisons bound accidental and concurrent drift but do not make separate network operations atomic.

The historical `release.yml` and `release-v0.1.1.yml` workflows are sealed evidence for the v0.1.0 and v0.1.1 releases; their own source guards preserve those controllers, and the forward controller does not inspect or reinterpret their contents.
The active `publish-release.yml` workflow, displayed as `Publish release`, is the only forward release controller.
`release/current-controller.json` contains one fixed-schema `current` and `predecessor` identity pair; each identity contains its canonical stable tag, exact release commit, and annotated tag object, while `current` also carries the non-empty, unique numeric Minecraft versions selected for representative published-client checks.
A new release replaces that pair in place after review instead of adding another workflow, branch, version-specific step, or prose inventory.
The dispatched tag must equal the metadata's current tag, the pair must be the latest two annotated stable release tags, the predecessor commit must be an ancestor of the current commit, and every representative Minecraft version must own regular runtime and integration project blobs before the build and exactly one generated Maven artifact and Modrinth manifest entry afterward.
The controller reads the metadata and every controller-owned verifier as regular Git blobs from the exact controller commit, validates their Git modes and hashes with replacement objects disabled, and requires the exact controller commit to remain the current `origin/master` head.
Both identities must resolve to annotated tags whose signatures GitHub reports as verified, whose tag objects and target commits match the metadata, and whose root project versions match their tags.
All release tags matching `refs/tags/v*` are covered by `release/github-release-tag-ruleset.json` and its audited receipt.
The wildcard ruleset has no bypass actors, forbids update including fetch-and-merge, and forbids deletion; its audited revision must remain unchanged at every release mutation boundary.
The unprotected preflight freezes both identities as job outputs only after exact-controller, signed-tag, required-CI, root-version, ancestry, wildcard-ruleset, and current/controller Pages provenance checks pass.
The protected job materializes the same controller bundle, compares its metadata to the frozen outputs, and re-fetches and compares both tag identities, their ancestry, the exact controller master, the wildcard ruleset, Pages evidence, and the clean tagged workspace before each external mutation.
The ordered publication project matrix owns the actual Maven artifact IDs, aggregate publication tasks, and the non-empty, unique `build/release/maven-coordinates.txt` inventory; immutable tags created before that generator retain a controller-validated fallback to their tracked legacy exact-coordinate inventory.
Central inventory sizes come from the selected generated or immutable-legacy inventory and the publication suffix, detached-signature, and checksum relationships; Modrinth and GitHub inventory sizes come from the generated manifest and the one-signature-per-JAR plus `SHA256SUMS` bundle relationship.
Receipt fields must equal those derived inventories, so adding or removing a supported runtime changes the generated evidence without requiring an artifact-list, release-number, or fixed-count edit in the controller.
Central preflight distinguishes wholly absent content from a complete exact publication in both the public repository and authenticated Publisher Portal.
Only the wholly absent pair may invoke the single Vanniktech publication task; partial, conflicting, or cross-service state stops before any write, while an exact publication is verified and reused idempotently.
Representative client task paths are generated from the frozen metadata array rather than written into the workflow, and the verified controller bundle derives the setup-java matrix from the tagged source's version catalog.
Maven Central publication and the immutable GitHub Release complete before Modrinth review submission, so an externally pending Modrinth approval does not block or roll back either public service.
Normal Modrinth staging accepts only the generated predecessor or current project-body lineage, appends only missing manifest entries, and never replaces historical versions.
Before protected final verification starts, an unprotected fresh-runner job with no repository-token permissions, checkout credential, or release secret anonymously loads the same exact-controller metadata, fetches both public tags, and runs Skill preview and installation checks against both frozen source trees.
The secret-bearing verification job then re-proves the complete predecessor release from a detached worktree with that source's own Portal, Central, GitHub bundle, Modrinth, Pages, and tagged Skill contracts before allowing the current project-body finalizer.
The current and predecessor body states are monotonic across every controller sharing the legacy release concurrency group, and unrelated body, metadata, status, tag, controller, artifact, or public-service drift fails closed.

Release-specific recovery contracts under `release/` are executable controller inputs, not reader guidance or templates for later releases.
The controller materializes them only while frozen metadata explicitly binds the relevant release pair; otherwise the normal path cannot access them.
Their detailed invariants live with their scripts and tests, and they must be retired atomically with every controller reference after the bound recovery is complete.

The root `dokkaGenerate` task aggregates every published module into `build/dokka/html`.
The Documentation workflow invokes `:integration:docs:checkDokkaPagesStaging` only from the exact `master` head; that task depends on the fully qualified root `:dokkaGenerate` and verifies the generated API site.
The workflow deploys the resulting `build/dokka/html` directory through GitHub Pages' artifact and OIDC deployment path.
Before any repository-local action runs and again immediately before deployment, the workflow requires its checkout to equal the exact `origin/master` head and requires controller metadata to identify the current annotated tag, commit, tag object, latest-release order, and ancestry.
Release-tag pushes and manual dispatches never execute the Documentation workflow; every `master` push reconstructs the release inventory from the trusted controller definition.
The deployable `github-pages` artifact and its separate immutable-release evidence artifact are retained for thirty days so a delayed protected release approval can still revalidate the exact archives from the same run.
The full-history checkout lets `release/stage-versioned-pages.sh` reproduce tagged documentation in a detached worktree on later `master` runs and copy each independently checked site into `build/dokka/html/releases/{version}` without changing the current root documentation or recursively nesting older release trees.
Repository settings must select GitHub Actions as the Pages source, configure `github-pages-controller` with administrator bypass disabled and exactly the `master` branch policy, and retire `github-pages` with administrator bypass disabled and no branch or tag policy.
The build job receives read access to actions and contents so it can freeze the upload-pages artifact digest that the composite action does not expose, the release-evidence job receives only read access to contents, and only the deploy job receives read access to actions, contents, and deployments plus `pages: write` and `id-token: write`.

Qodana runs its recommended JVM inspection profile in CI without a baseline.
The workflow makes every Java toolchain declared by the version catalog available to the host-side native Qodana process so it can resolve each module model and its dependencies.
It restores the Gradle user home read-only, compiles every `classes` and `gametestClasses` boundary, and assembles the five plain common jars referenced by Loom's nested-library model before inspection without assembling remapped distribution jars.
The workflow retains those analysis inputs for the IDE model and stops only the Gradle daemons before analysis to release their memory and file handles.
Qodana's bootstrap invokes Gradle with configuration on demand disabled, the analysis-only `strata.completeIdeaModel` project property, and Loom's official `fabric.loom.ci` system property.
The CI property keeps mapped binary dependencies in the IDEA modules while preventing Loom from downloading and remapping optional dependency source artifacts.
The analysis-only property generates the official Gradle IDEA project and augments each versioned module with the real compile classpath and, for integration projects, the real test and GameTest classpaths and source roots.
This preserves one canonical physical copy of compatible mapped sources while preventing IntelliJ from assigning linked roots to dependency-free directory modules.
The explicitly authorized bootstrap may replace its generated model while Qodana moves between pull-request revisions, and exposes that graph through disposable `.idea/modules.xml`, `*.iml`, and project-SDK metadata that binds inherited analysis to Qodana's registered JBR while retaining each module's Java language level.
The `rootJavaProjects` setting opens that model directly instead of asking Qodana to reconstruct a different Gradle model.
The workflow then checks the emitted project structure against every discovered versioned runtime and integration owner, including their SDK, dependency, runtime-source, and GameTest-source boundaries, so a partial project import cannot pass only because inspections were excluded.
The much larger Qodana IDE cache is not persisted and is removed after the run to preserve hosted-runner disk space; it may be enabled only after the complete-model import is green and its key covers every IDE-model input, while required analysis inputs remain ordinary reproducible build outputs rather than cache-only state.
Static-analysis rules are enabled when they produce actionable improvements; rules that systematically make code less clear are disabled with a durable rationale in the checked-in configuration.

The nonpublished `integration:minecraft-fabric-26.2` and `integration:minecraft-fabric-26.1` modules compile the same neutral loaded-client suite against their exact game and Fabric API dependencies.
`./gradlew :integration:minecraft-fabric-26.2:runClientGameTest` fixes the viewport, GUI scale, locale, resource profile, and pointer state, then requires exact native-Screen, Fabric-adapter, and headless ARGB equality for the existing screen scenes before writing build-only evidence.
Its independent resource-font scenes additionally require exact native metrics, glyph texels, and layout, with final native image differences accepted only by the [font GPU evidence gate](font-resources.md#acceptance-evidence); Fabric and headless output remain exact.
For explicit Canvas backend verification, run that task separately with `'-Pstrata.canvas.backend=opengl'` and `'-Pstrata.canvas.backend=vulkan'`; quote these dotted property arguments in PowerShell.
These runs write separate `minecraft-parity-opengl` and `minecraft-parity-vulkan` build directories and require the actual device backend to match the request; a driver fallback is a failed Vulkan test, not Vulkan evidence.
Native Canvas scenes independently verify fixed texture texels, a custom offscreen renderer, alpha, clipping, ordering, GUI scales, resize, and lifecycle before comparing same-generation portable capture pixels.
The 26.2 task defaults to `strata.canvas.scope=full`, which runs the unchanged complete shared suite.
An explicit `'-Pstrata.canvas.scope=canvas-only'` selects bounded backend acceptance and writes separate `minecraft-canvas-parity-<backend>` evidence.
For example, `./gradlew :integration:minecraft-fabric-26.2:runClientGameTest '-Pstrata.canvas.backend=vulkan' '-Pstrata.canvas.scope=canvas-only'` runs every existing Canvas texture, custom-renderer, lifecycle, capacity, queued-consumption, same-generation capture, native input-reset, and partial-producer-failure case, plus actual Canvas/Slot ordering with a server-seeded inventory slot.
The same properties apply to `runProductionClientGameTest`, which writes `minecraft-production-canvas-parity-<backend>` evidence while loading the packaged integration and runtime JARs.
On Vulkan, this Canvas-specific scope keeps the native GLFW surface and swapchain extent stable while changing Minecraft's logical viewport, framebuffer dimensions, GUI scale, and main render target; OpenGL retains Fabric's native resize path.
This verifies Canvas target resize and scale behavior without claiming physical operating-system window resize coverage.
This scope excludes unrelated native reference-screen parity, the non-Canvas component showcase, and inventory click-synchronization scenarios; it cannot establish a full-suite Vulkan pass or generate the component showcase.
The full scope continues to exercise Fabric's physical window-resize path in those unrelated scenes, so its backend result remains separate from Canvas-specific acceptance.
Use it to record Canvas evidence separately from complete-suite failures, retaining the failing run's logs and any comparison logs with their exact runtime and graphics configuration.
A comparison that does not display Canvas but still loads its runtime and mixins is not a baseline with that runtime removed and does not establish a driver root cause.
No scope changes a pixel oracle or substitutes a CPU-only renderer for native GPU acceptance.
The full and Canvas-only scopes arm the same test-only observer around actual client shutdown only after their selected cases succeed.
The diagnostic `'-Pstrata.canvas.scope=terminal-only'` skips feature scenes and proves only the identical mixed portable/native terminal queue and device shutdown; it is not Canvas feature acceptance.
It writes `minecraft-canvas-terminal-<backend>` development evidence or `minecraft-production-canvas-terminal-<backend>` production evidence.
It queues a native Canvas between portable background and foreground layers without consuming their GUI work and requires a fresh invocation-bound receipt proving that original shutdown returned, the queue was discarded, and native target, portable texture, and renderer ownership reached zero.
Development and primary production runs write `strata-canvas-terminal.properties` beside their scope-specific parity evidence; the published-coordinate production task writes `canvas-shutdown/<taskName>.properties` under the integration build directory.
The terminal receipt records `suiteScope`, `verifiedChecks`, `excludedChecks`, and the actual `menuBackgroundBlurriness` option, which must remain unchanged between arming and entry into native shutdown after the harness restores the test viewport through its backend-owned path.
Gradle rejects a receipt from a different scope or invocation and validates the recorded blur option against Minecraft's supported range.
These receipts prove the actual shutdown boundary, while the Minecraft-independent tests separately cover fences that remain unsignalled for arbitrarily many frames.
Minecraft-dependent facts unavailable from the Minecraft evidence catalog are verified against official local client jars; a documentation-provider quota failure does not authorize guessing versioned APIs.
`./gradlew :integration:minecraft-fabric-26.1:runClientGameTest` applies the same acceptance conditions to 26.1 and writes a version-qualified build receipt.
Both unobfuscated releases also provide `runProductionClientGameTest`, which packages the integration suite and runs it against the actual runtime Mod jar with its nested common libraries in a separate disposable run directory.
Every supported integration project's `check` requires its development and production-jar loaded gates; the separately selected `runPublishedCoordinateClientGameTest` additionally verifies externally resolved published artifacts where that task is available.
The unqualified `./gradlew check koverHtmlReport koverXmlReport -Pkover` selects these project checks, the isolated CPU font contracts, and the configured native-to-offline font comparison tasks; the qualified `:check` task alone intentionally checks only the root project.
The nonpublished `integration:minecraft-fabric-1.21.11` through `integration:minecraft-fabric-1.20` modules compile the complete shared legacy loaded-client suite and the matching input-generation and version-name roots against their remapped adapters, required Java toolchains, and exact Fabric API dependencies.
Minecraft 1.21.4 and later use the Fabric Client GameTest adapter source root, while 1.21.3 through 1.20 use a standalone client entrypoint because their exact official Fabric API fixtures predate that module; 1.20.4 through 1.20.2 share the runner bridge needed for their dirt-message and level-cleanup APIs, while 1.20 and 1.20.1 share the compiler-proven preceding readiness and level-clear variant in exact owning projects.
Fabric API is confined to these integration modules; the published runtimes do not depend on it.
The generated Minecraft CI plan invokes `ciMinecraftCheck` for every discovered version pair and writes version-qualified build evidence after exercising each remapped adapter inside its actual client.
Those checks package and remap every applicable integration test Mod and runtime Mod, then repeat the loaded suite from the production jars with their nested common runtime jars.

Integration jar remapping includes the GameTest compile classpath because those jars also package the GameTest source set; this lets Loom resolve inherited Minecraft methods through the external runtime's screen hierarchy.
The production loaded suite calls an inherited screen method through the concrete public runtime type to verify that boundary after remapping.

The nonpublished `integration:docs` module owns two showcase tasks that render the compiled API-only examples on the CPU without launching Minecraft or creating a GPU context.
Run these isolated cross-project tasks with configuration-on-demand disabled so every project contributing compiled examples and renderers is configured before Gradle resolves its classpath.
`./gradlew :integration:docs:checkComponentShowcase --no-configure-on-demand` renders fresh headless frames into staging and checks documentation freshness without modifying repository files.
`./gradlew :integration:docs:generateComponentShowcase --no-configure-on-demand` performs the same rendering and synchronizes the combined `docs/components.md` showcase, PNG files, `docs/components/headless-render.properties`, and anchored root README region.
The generated Canvas component page uses its portable CPU source; native texture and custom-renderer acceptance remain separate loaded-game checks.
The generated TiledImage page uses twelve independent immutable CPU tiles and a content-position overlay; the loaded component parity gate renders the same compiled definition through Fabric.
Generated output is owned by the showcase generator; manual edits are reported as stale by the checker.

Both tasks read an explicit Minecraft client archive, asset index, indexed objects directory, and version manifest.
They always execute without build-cache reuse; Gradle records the objects directory's location rather than recursively fingerprinting its shared contents, and the launcher verifies every consumed input before publishing its receipt.
By default, Gradle provisions these raw resources through dedicated asset publications from `integration:minecraft-fabric-26.2`; provisioning may download missing official assets but does not start a game process or add Minecraft, Fabric, OpenGL, or GLFW classes to the documentation runtime classpath.
To supply existing resources instead, set all four properties; relative paths resolve from the repository root, and supplying all four avoids the integration project's asset-provisioning tasks:

```shell
./gradlew :integration:docs:generateComponentShowcase -Pstrata.showcase.clientJar=assets/minecraft-client.jar -Pstrata.showcase.assetIndex=assets/index.json -Pstrata.showcase.assetObjects=assets/objects -Pstrata.showcase.versionManifest=assets/version.json
```

The same properties apply to `checkComponentShowcase`.
Inputs remain read-only, must be regular files or the objects directory without symbolic links, and are checked against the version manifest and indexed object hashes.
The full manifest and asset index fence each generation but are not portable receipt identities; the receipt instead records the immutable client, selection contract, logical path sets, and hashes of resources actually consumed by rendering.
Only the inventory screen uses native image evidence: the launcher explicitly supplies `docs/evidence/minecraft-26.2-inventory.png` and its `.properties` receipt because that example requires a live server-backed binding.
Generation verifies the inventory image dimensions, Minecraft version, image hash, and current compiled example's LF-normalized source hash before copying it; it never launches a game or server to refresh that input.
The deterministic headless receipt records logical source and asset hashes, viewport dimensions, GUI scale, physical PNG dimensions, and each image's origin and hash without absolute paths or timestamps.
`Text`, `TextField`, and `TextArea` use GUI scale 2 at unchanged logical viewports; other components, the overview, and complete screens use scale 1.
This density controls original glyph sampling during rendering, not image enlargement afterward.
Animated examples publish the canonical frame at time zero; loaded verification requires exact full-frame pixels for a supported discrete animation phase, which may differ from that stored phase.

Native acceptance is independent of generation: `./gradlew :integration:docs:generateMinecraftShowcaseEvidence` runs the loaded gate and refreshes `docs/evidence/minecraft-26.2-parity.properties` plus the inventory image and receipt.
`./gradlew :integration:docs:checkMinecraftShowcaseParity` compares fresh native evidence with fresh headless showcase output, including each component's logical viewport and GUI scale.
The module's `check` task requires both documentation freshness and this native acceptance gate; the two targeted headless generation and freshness tasks do not require a loaded-game run.

The same module owns the public `skills/strata` package and its API-only compiled examples.
`./gradlew :integration:docs:checkStrataSkill :integration:docs:checkDocumentationLinks` discovers component and Modifier overloads from compiled API classes, pairs them with exact Kotlin source declarations, checks state and binding declarations against compiled public member fingerprints, verifies generated references byte-for-byte, and checks every repository-local README, docs, and skill link without changing tracked files.
`./gradlew :integration:docs:generateStrataSkill` deliberately synchronizes the five generated skill references, the anchored README installation and API-only example regions, and the canonical `docs/modrinth-project.md` body after a release-version, API, or example change.
The skill examples use a separate source set whose compile classpath contains only `:api`; ordinary application examples therefore cannot acquire a runtime import transitively.
The Pages workflow uses `docs/dokka-module.md` as the API landing-page introduction instead of the root README.
`generateDokkaModuleMarkdown` prepares an ignored build-only include whose GitHub reader links use the selected `strata.sourceRevision`, so tagged API sites link to their matching repository guides.
Reader guides and verified component images stay in the repository and are rendered on GitHub; they are neither copied into nor required by the current Dokka Pages artifact.
The staging checker scans checked source text for hard-coded `https://gh.s7a.dev/strata/` targets and requires a matching non-symbolic staged file, treating a trailing slash as `index.html`, before the Pages artifact is uploaded.
It verifies local links, anchors, and asset targets in inventoried HTML pages against the staged site.
`generateDokkaPagesInventory` writes the sorted public relative paths to `build/dokka/html/pages-public-urls.txt`, including the Dokka root, explicitly linked API files, `source-revision.txt`, and the canonical tag-and-commit `source-receipt.json`; each tagged copy retains its own self-contained inventory below its `releases/{version}` root.
Historical tagged sites keep the publication contract from their immutable source revision, including any older guide copies.
Final release verification downloads the selected `/releases/{version}/pages-public-urls.txt` from the configured Pages origin, requests every listed path relative to that immutable base, checks its `source-revision.txt`, and polls its receipt until both the release tag and exact commit are visible in a current final HTTP response.
The common JVM shard runs the pinned official actionlint container, parses every tracked release shell script with `bash -n`, and runs isolated regressions for tag replacement, ruleset drift and response normalization, release and controller Pages run/artifact/deployment binding, safe immutable-subtree comparison, archive receipt drift, global deployment ordering, CDN age handling, and bounded public polling before its Gradle gates.

Run `./gradlew :quality:benchmarks:jmh` for the temporary JSON report and follow the methodology and acceptance gates in [Rendering performance](performance.md).

## Documentation ownership

Keep canonical API and runtime contracts, reader guides, release notes and publication bodies, compiled examples, and deterministic generated images and receipts in Git.
Update generator sources and regenerate checked outputs instead of editing generated documents by hand.
Keep task plans, working notes, status reports, and unfinished drafts under the ignored `build/` directory; promote durable decisions into the relevant canonical guide when the work is complete.
Do not retain release-incident timelines or external-service status snapshots in reader guides; encode any required recovery boundary in executable contracts and tests.
Loaded worlds, transient screenshots, raw benchmark output, and test or coverage reports remain untracked build outputs.
