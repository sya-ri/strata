# Render monitoring

`RuntimeUiDiagnosticsOwner` is an internal runtime capability implemented by the retained session bridge, Minecraft host, and every Fabric screen. Tests open the normal production `ScreenDefinition`, then obtain `startRenderMonitoring()` from the current screen on its owner thread. Application definitions need no runtime imports or diagnostics arguments.

Only one monitor may be active per session. `checkpoint()` resets interval counts, `snapshot()` returns detached unmodifiable evidence, `findNodes(key)` returns current session node IDs, and `close()` releases the collector without closing the screen. All operations reject reentry and foreign threads; terminal tree failure or close ends monitoring automatically.

After terminal failure, `snapshot()` can read one final detached interval, including failure and cleanup counts, until the caller explicitly closes the monitor. Runtime nodes, state, callbacks, and subscriptions are already released. This preserves diagnosable failure evidence without keeping a failed screen alive; it does not retain frame history.

IDs distinguish equal keys under different parents and remain stable while the same node is retained. Snapshots contain ID, parent ID, component/modifier kind, implementation type name, retirement flag, and counts. They contain no node, key, state value, message body, or callback references.

Counters record frame attempts/success/failure/cache hits, source subscriptions, projections/equal outputs/consumer deliveries, actual root/Observe/direct-component/row evaluations, node creation/update/disposal, and actual measure/layout/paint/overlay/semantics callbacks. Cached `dynamicChildren()` reads do not count as content evaluation. Initial/source/replacement/parent-definition/structure/ancestor causes are distinct. Pre-input geometry has its own operation category.

Collection is disabled by default: there is no collector, diagnostic event allocation, timestamp, stack capture, or key formatting. Enabled collection uses fixed primitive counters and at most 4,096 node records per interval, without frame history. Checkpoint drops retired records. Overflow remains explicit until a new monitor starts because omitted live identities cannot be reconstructed from incomplete interval evidence; tests must reject overflowed snapshots. Close clears all retained node and observer references.

Native tests first wait for resource loading and initial painting to stabilize, then checkpoint. Publish state and wait for a successful host frame to advance; elapsed sleep alone is not evidence. Equal mapped output must leave dependent content, node update, measure, layout, paint, and semantics counts unchanged and reuse the previous frame. Compare the existing native preparation, rasterization, and texture-upload counters too; Minecraft extraction and host-frame calls continue normally.

Tests also compare diagnostics against independent primitive callback counters and virtual-list row callbacks. A count is actual invocation work, not a statement that every invocation was avoidable. Necessary text geometry propagation and continuous Minecraft rendering are valid. Timing and allocation comparisons are diagnostic measurements under documented conditions; deterministic work counts, retention limits, and image equality are the CI gates.
