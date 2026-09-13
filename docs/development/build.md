# Build and verification

Use this document to build and check Strata locally.
[CI](ci.md), [release publication](release.md), and [documentation maintenance](documentation.md) describe their separate operational boundaries.

## Shared Kotlin targets

The API and retained core publish JVM variants at their existing `strata-api` and `strata-runtime-core` coordinates.
Multiplatform consumers select `strata-api-multiplatform` and `strata-runtime-core-multiplatform`; their Gradle metadata resolves JVM to those existing artifacts and JavaScript to the corresponding `-js` artifacts.
The canonical release inventory includes all target artifacts.
Common behavior tests run on JVM, Node.js, and headless Chrome, while JVM-only concurrency tests continue to exercise real threads.
KMP documentation uses Dokka HTML in the conventional `javadoc` classifier because the Javadoc output plugin does not support multiplatform declarations.

## Environment

Run commands from the repository root with the checked-in wrapper: `./gradlew`, or `.\gradlew.bat` in PowerShell.
Install the Java toolchains declared by the version catalog; automatic toolchain downloads are disabled.
The [wrapper configuration](../../gradle/wrapper/gradle-wrapper.properties) and [version catalog](../../gradle/libs.versions.toml) are the source of build-tool and dependency versions.
Common modules use the baseline toolchain, while adapters use their target's required toolchain from the [compatibility reference](../reference/compatibility.md).

## Quality checks

Run affected module checks while developing, then run the required aggregate checks before review:

```shell
./gradlew check koverHtmlReport koverXmlReport -Pkover
```

The unqualified `check` includes each selected project's checks; `:check` alone checks only the root project.
The full command includes development and production loaded-client gates, isolated CPU font contracts, and configured native-to-offline comparisons.

Every Kotlin compilation uses explicit API mode and treats warnings as errors.
Canvas GUI-consumption hooks compile against the Mixin and MixinExtras versions already supplied by the configured Fabric Loader.
Those dependencies are compile-only, remain in the version catalog, and add neither a nested runtime library nor a Fabric rendering API requirement.
The exact Loader dependency versions are checked against [its primary build configuration](https://github.com/FabricMC/fabric-loader) and the [MixinExtras setup contract](https://github.com/LlamaLad7/MixinExtras#setup).
Detekt and Kotlinter are applied to all project modules.
The Canvas family roots and loaded-test roots are explicitly included in Detekt's source inputs, because [its generic task](https://detekt.dev/docs/gettingstarted/gradle/#available-plugin-tasks) otherwise scans only conventional project directories.
Kover exposes HTML and XML reports without enforcing a coverage threshold.
Run aggregate coverage with `./gradlew :koverHtmlReport :koverXmlReport -Pkover`; each fully qualified root report task first runs the ordinary JVM test suite selected by `koverJvmTests` without discovering same-named tasks in unrelated projects.
`gradle/kover-jvm-projects.txt` is the single source of truth for that aggregation boundary: it includes modules with ordinary JVM tests and excludes testless remapped adapters, loaded-client-only integrations, and benchmarks so coverage does not configure or compile unrelated Loom projects.

## Packaging and publication checks

When publication code changes, run `./gradlew publishToMavenLocal` and inspect artifact contents and publication metadata.
The [release procedure](release.md) defines external publication and credentials.

Each versioned Fabric artifact packages the `api`, `runtime:core`, `runtime:headless`, `runtime:minecraft`, and `runtime:minecraft-fonts-lwjgl` jars under `META-INF/jars` exactly once.
Its Java toolchain and distribution mapping follow the typed target matrix summarized in the [compatibility reference](../reference/compatibility.md).
Unobfuscated clients use the catalog-selected Fabric Loom plugin in no-remap mode; remapped clients compile against official Mojang mappings and remap their distribution jars with the catalog-selected remap plugin.
Every versioned Fabric runtime module declares an exact Minecraft requirement and catalog-derived Loader, Fabric Language Kotlin, and Java lower bounds; none uses Fabric API at runtime.
Remapped runtimes declare Loader through Loom's `modCompileOnly` configuration so their own installer metadata and Mixin libraries are resolved even when no integration project is configured.
Unobfuscated runtimes use `compileOnly`, which their Loom dependency pipeline processes directly.
The shared artifact verifier requires exact catalog-derived Loader and Mixin identities in the final JAR manifest, rejecting missing, unresolved, or mismatched build metadata without rewriting the archive; every Maven publication runs the artifact and publication-metadata verifiers before uploading or installing files.

Integration jar remapping includes the GameTest compile classpath because those jars also package the GameTest source set; this lets Loom resolve inherited Minecraft methods through the external runtime's screen hierarchy.
The production loaded suite calls an inherited screen method through the concrete public runtime type to verify that boundary after remapping.

## Loaded-client verification

Each versioned integration project verifies its exact adapter through development outputs and packaged production jars.
The legacy viewport fixture requests both Minecraft's test dimensions and the real GLFW window size, then requires two matching native/window/render-target samples.
Fabric's test Window mixin can substitute Minecraft's dimensions, so those values alone do not prove that a physical resize completed.
Run `:integration:minecraft-fabric-<version>:runClientGameTest` and `:integration:minecraft-fabric-<version>:runProductionClientGameTest` for the target being changed.
Every supported integration project's `check` requires both gates.
Where provided, `runPublishedCoordinateClientGameTest` additionally checks externally resolved publications.
The [adapter process](../development/minecraft-versions.md) describes native boundaries and integration runner ownership.

The nonpublished `integration:minecraft-fabric-26.2` and `integration:minecraft-fabric-26.1` modules compile the same neutral loaded-client suite against their exact game and Fabric API dependencies.
`./gradlew :integration:minecraft-fabric-26.2:runClientGameTest` fixes the viewport, GUI scale, locale, resource profile, and pointer state, then requires exact native-Screen, Fabric-adapter, and headless ARGB equality for the existing screen scenes before writing build-only evidence.
Its independent resource-font scenes additionally require exact native metrics, glyph texels, and layout, with final native image differences accepted only by the [font GPU evidence gate](../development/font-verification.md#acceptance-evidence); Fabric and headless output remain exact.

Legacy automated screenshot suites also wait for the requested physical viewport to match GLFW, the native Window framebuffer, and the render target across client ticks.
This avoids assuming that an asynchronous OS resize completed when its request returned.
Native Canvas screenshot scenes additionally fence on the runtime's committed host-frame counter through `MinecraftCanvasFrameFence` instead of waiting a fixed tick count: a tick proves time passed, while a committed host frame after the scene signal proves the frame the signal observed completed its render call before the main framebuffer is read.
The fence fails when the runtime counter becomes unreadable rather than silently degrading to fixed-tick behavior.
Assertion receipts record the GUI scale, lease, and host-frame counters alongside the retained PNG, so a failed pixel assertion keeps its synchronization evidence.
Timeouts and screenshot failures report actual dimensions and do not crop, rescale, or weaken pixel assertions.

## Canvas backend and terminal checks

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

## Manual OS IME verification

Use `:integration:minecraft-fabric-26.2:runManualIme` to open the isolated native editor with normal OS keyboard callbacks.
This opt-in task is separate from `check` and from Fabric Client GameTest, whose `InputConstantsMixin` deliberately prevents native keyboard and mouse callback installation.
Do not treat a synthetic PreeditEvent or Fabric TestInput result as proof of operating-system composition.
The normal run has its own build directory and must not connect to a multiplayer server.
After any first-run accessibility page, the fixture opens from the title screen.
Use the installed Japanese IME to type `nihongo`, leave the composition and candidate window active while the update label advances, convert and confirm `日本語`, then use Enter to insert a newline.
Verify the caret, candidate selection, and text visually before selecting Finish test.
The task requires a fresh invocation-specific `manual-ime-evidence/manual-os-ime.txt` receipt with the converted phrase, newline, stable input-node identity, and concurrent label updates.
A closed window or an old receipt cannot pass this task.
The receipt establishes confirmed input and retained nodes; the operator's observation establishes candidate/preedit continuity.

## Documentation and benchmarks

[Documentation maintenance](documentation.md) lists the focused link, compiled-example, generation, and freshness checks.
Run `./gradlew :integration:docs:generateReadmeDemo --no-configure-on-demand` to regenerate the README GIF, or `./gradlew :integration:docs:checkReadmeDemo --no-configure-on-demand` to verify it without modifying tracked files.
The [README demo generation contract](documentation.md#readme-code-and-screen-demo) describes its compiled examples, resource inputs, and validation.
Run `./gradlew :quality:benchmarks:jmh` for temporary measurements, then apply the [performance review contract](../development/performance.md).
Test, coverage, native evidence, and benchmark reports belong under their module build directories and must be recreated for the revision being reviewed.
