package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.jdi.ArrayReference
import com.sun.jdi.StringReference
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.MethodExitEvent
import dev.s7a.strata.runtime.remote.RemoteImageCodec

/**
 * Untimed counts of actual pixel encoding calls and returned source-pixel snapshot arrays on both runtime archives.
 * These selected sites exclude ByteBuffer, immutable Bytes, wire/framing arrays and object/map allocation.
 * No CPU, total allocation, native upload or throughput result is inferred from these counts.
 */
public object RemoteImageEncodingEvidence {
    /**
     * Accepts fresh counts/child-provenance paths and the same common immutable source/archive plan as JMH/CPU.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        RemoteUntimedObservation.run(args, RemoteImageProbe::class.java, RemoteImageBenchmark::class.java, listOf(RemoteImageCodec::class.java.name, "dev.s7a.strata.render.DrawImageSnapshot"), "remote-image-encoding-observation-v1", Observation())
    }

    /**
     * Only one typed case and counters are retained; no debugger object, frame, image or production result survives.
     */
    private class Observation : RemoteProbeObservation {
        override val rows = JsonArray()
        override val active: Boolean get() = current != null
        private var current: JsonObject? = null
        private var owner: Long? = null
        private val seen = mutableSetOf<Pair<RemoteImageBenchmark.Extent, RemoteImageBenchmark.Workload>>()

        override fun enter(event: MethodEntryEvent) {
            val method = event.method()
            if (method.declaringType().name() == RemoteImageProbe::class.java.name) {
                when (RemoteProbeMarker.decode(method.name())) {
                    RemoteProbeMarker.Begin -> {
                        check(current == null)
                        val args = event.thread().frame(0).getArgumentValues()
                        val extent = RemoteImageBenchmark.Extent.valueOf((args[0] as StringReference).value())
                        val workload = RemoteImageBenchmark.Workload.valueOf((args[1] as StringReference).value())
                        check(seen.add(extent to workload))
                        owner = event.thread().uniqueID()
                        current = JsonObject().apply {
                            addProperty("extent", extent.name)
                            addProperty("workload", workload.name)
                            addProperty("observed_operations", 2)
                            addProperty("pixel_encoding_calls", 0L)
                            addProperty("source_snapshot_arrays", 0L)
                            addProperty("source_snapshot_bytes", 0L)
                        }
                    }
                    RemoteProbeMarker.Finish -> {
                        check(owner == event.thread().uniqueID())
                        rows.add(checkNotNull(current))
                        current = null
                        owner = null
                    }
                    null -> Unit
                }
                return
            }
            val row = current ?: return
            check(owner == event.thread().uniqueID())
            if (RemoteImageObservedMethod.decode(method.name()) == RemoteImageObservedMethod.EncodePixels) row.addProperty("pixel_encoding_calls", row.get("pixel_encoding_calls").asLong + 1)
        }

        override fun exit(event: MethodExitEvent) {
            val row = current ?: return
            if (RemoteImageObservedMethod.decode(event.method().name()) != RemoteImageObservedMethod.CopyArgb) return
            check(owner == event.thread().uniqueID())
            val pixels = event.returnValue() as ArrayReference
            row.addProperty("source_snapshot_arrays", row.get("source_snapshot_arrays").asLong + 1)
            row.addProperty("source_snapshot_bytes", row.get("source_snapshot_bytes").asLong + pixels.length().toLong() * Int.SIZE_BYTES)
        }

        override fun complete(): Boolean = current == null && seen.size == 36
    }
}
