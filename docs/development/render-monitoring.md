# Performance measurement boundaries

Production API and runtime artifacts contain no performance collectors, monitoring entry points, optional diagnostic observers, cumulative work counters, or disabled measurement hooks.
A disabled callback still couples instrumentation to ordinary processing and is not an accepted substitute for removing the hook.
Application frame timestamps, protocol deadlines, cache admission limits, resource ownership and GPU-consumption fences remain part of normal behavior.
Their authoritative current state must not be removed merely because a test also inspects it.

The optional performance testkit owns operation timers, CPU and allocation collection, sample storage, distributions, provenance and report serialization.
Use `JvmPerformanceRunner`, `JvmPerformanceSchedule` or the existing JMH harness to surround complete public operations.
Preparation, independent assertions and report storage stay outside the measured interval.
Native collection observes the real application through framework frame boundaries; it does not install Strata-specific production callbacks.
A diagnostic or externally instrumented run must be identified separately from an uninstrumented timing run, including its actual runtime and collector bytes.

## Correctness and work evidence

Test fixtures may count their own genuine content, node, subscription, texture-factory and cleanup callbacks.
Keep those probes in test or verification sources and preserve the original operation rather than implementing a replacement runtime.
Use actual frame identity, immutable command snapshots, complete pixel comparisons, input results and resource release for independent behavioral verification.
External profilers or test-only instrumentation can supply additional work observations when needed; they must not be packaged with the production runtime or silently enabled during ordinary use.

Unavailable work or upload metrics remain explicitly unavailable.
Never substitute zero, infer callback counts from source code, or treat a missing metric as proof that no work occurred.
A requested `WorkExpectation` fails when its actual evidence is unavailable.
The testkit's `RuntimeWorkMonitor` remains a compatibility reader for previously released runtimes that expose the former internal capability; it reports unavailable for the current production runtime.
The removed `RuntimeUiDiagnosticsOwner` and `UiRenderMonitor` entry points are internal runtime APIs and are no longer provided by current hosts or screens.

## Overlapping content and overlays

Updating a lower node does not mean drawing its pixels directly over the previous final image.
The paint pipeline regenerates dirty local commands, then assembles the complete ordered display list, including cached foreground sibling commands, child clips, post-child overlays and root overlays.
Unchanged foreground callbacks can remain cached while their commands still participate in composition.
A child geometry change propagates measure/layout and conservative paint invalidation to ancestors.
Semi-transparent foreground pixels depend on the current background; clearing lower content must reveal that background without retaining the previous color.

Regression fixtures verify callback reuse through their own probes and compare complete ordered commands and pixels with independent references.
Native portable reuse keys a complete ordered command run, so a changed lower command can require recomposition with an unchanged overlay.
Strata does not promise per-node damage rectangles or independent textures for every overlapping component.
Reduced callback work alone establishes no GPU-time or FPS improvement.
