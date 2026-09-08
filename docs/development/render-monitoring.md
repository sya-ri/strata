# Render monitoring

`RuntimeUiDiagnosticsOwner` is an internal runtime capability implemented by the retained session bridge, Minecraft host, and every Fabric screen. Tests open the normal production `ScreenDefinition`, then obtain `startRenderMonitoring()` from the current screen on its owner thread. Application definitions need no runtime imports or diagnostics arguments.

Only one monitor may be active per session. `checkpoint()` resets interval counts, `snapshot()` returns detached unmodifiable evidence, `findNodes(key)` returns current session node IDs, and `close()` releases the collector without closing the screen. All operations reject reentry and foreign threads; terminal tree failure or close ends monitoring automatically.

After terminal failure, `snapshot()` can read one final detached interval, including failure and cleanup counts, until the caller explicitly closes the monitor. Runtime nodes, state, callbacks, and subscriptions are already released. This preserves diagnosable failure evidence without keeping a failed screen alive; it does not retain frame history.

IDs distinguish equal keys under different parents and remain stable while the same node is retained. Snapshots contain ID, parent ID, component/modifier kind, implementation type name, retirement flag, and counts. They contain no node, key, state value, message body, or callback references.

Counters record frame attempts/success/failure/cache hits, source subscriptions, projections/equal outputs/consumer deliveries, actual root/Observe/direct-component/row evaluations, node creation/update/disposal, and actual measure/layout/paint/overlay/semantics callbacks. Cached `dynamicChildren()` reads do not count as content evaluation. Initial/source/replacement/parent-definition/structure/ancestor causes are distinct. Pre-input geometry has its own operation category.

Collection is disabled by default: there is no collector, diagnostic event allocation, timestamp, stack capture, or key formatting. Enabled collection uses fixed primitive counters and at most 4,096 node records per interval, without frame history. Checkpoint drops retired records. Overflow remains explicit until a new monitor starts because omitted live identities cannot be reconstructed from incomplete interval evidence; tests must reject overflowed snapshots. Close clears all retained node and observer references.

Native tests first wait for resource loading and initial painting to stabilize, then checkpoint. Publish state and wait for a successful host frame to advance; elapsed sleep alone is not evidence. Equal mapped output must leave dependent content, node update, measure, layout, paint, and semantics counts unchanged and reuse the previous frame. Compare the existing native preparation, rasterization, and texture-upload counters too; Minecraft extraction and host-frame calls continue normally.

Tests also compare diagnostics against independent primitive callback counters and virtual-list row callbacks. A count is actual invocation work, not a statement that every invocation was avoidable. Necessary text geometry propagation and continuous Minecraft rendering are valid. Timing and allocation comparisons are diagnostic measurements under documented conditions; deterministic work counts, retention limits, and image equality are the CI gates.

## Overlapping content and overlays

Updating a lower node does not mean drawing its pixels directly over the previous final image.
The paint pipeline regenerates dirty local commands, then assembles the complete ordered display list, including cached foreground sibling commands, child clips, post-child overlays, and root overlays.
Unchanged foreground content and paint callbacks can remain cached while those commands still participate in composition.
This applies to a paint-only change below an overlay; a child geometry change propagates measure/layout and conservative paint invalidation to ancestors.
An ancestor overlay may use child geometry cached during measure/layout even when its own outer bounds remain the same, so its paint callback can run again without reevaluating the screen or unrelated sibling content.
Semi-transparent foreground pixels depend on the current background; clearing lower content must reveal that background without retaining the previous color.

`Paint`, `OverlayPaint`, and `RootOverlayPaint` count callback invocations, not command submission, blended pixels, or native rasterization.
A zero foreground callback count therefore does not imply zero foreground composition cost.
The native portable cache keys a complete ordered command run: a changed lower command can require rasterizing and uploading the layer containing an unchanged overlay.
Strata does not promise per-node damage rectangles or independent cached textures for every overlapping component, and fully occluded nodes are not exempt from state evaluation.
Use the native preparation/rasterization/upload counters alongside UI counters before making performance claims about a layered screen.

Regression tests change and clear a lower sibling under opaque and translucent foregrounds and compare every pixel with manually calculated colors.
Separate core assertions retain child clips and both overlay kinds while invoking only the changed child's paint callback.
The shared loaded-client scenario changes direct state inputs beneath clipped translucent post-child paint and opaque root paint: paint-only progress updates keep overlay callbacks at zero while portable composition does run; a text-width change also runs the ancestor overlay callbacks once after geometry propagation.
The updated screenshot must equal the literal headless reference.
