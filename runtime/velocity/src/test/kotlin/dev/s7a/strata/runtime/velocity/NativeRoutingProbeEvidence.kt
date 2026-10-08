package dev.s7a.strata.runtime.velocity

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.jdi.ArrayReference
import com.sun.jdi.Bootstrap
import com.sun.jdi.IntegerValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.StringReference
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.MethodExitEvent
import com.sun.jdi.event.VMDeathEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.request.EventRequest
import dev.s7a.strata.performance.PerformanceJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Untimed debugger observations of actual native event arrays, public decoder returns and endpoint assembly.
 * Callback, public-decode and owner intervals are disjoint; counts are never inferred from a source formula.
 * Actual accessor/forwarding/inbox identities are compared before releasing one bounded row's debugger references.
 * Suspension and instrumented child duration are not performance evidence.
 */
@Suppress("StringLiteralComparison") // External compiled class/method/field names are decoded at this JDI adapter boundary.
internal object NativeRoutingProbeEvidence {
    /**
     * Accepts one fresh report; launches the exact frozen fixture/runtime classpath with the existing JDK opens.
     */
    @JvmStatic
    @Suppress("LongMethod", "CyclomaticComplexMethod") // Keeps debugger setup, explicit interval transitions and teardown in one untimed transaction.
    fun main(args: Array<String>) {
        require(args.size == 1)
        val destination = Path.of(args[0]).toAbsolutePath().normalize()
        val child = destination.resolveSibling("${destination.fileName}.child.json")
        require(Files.exists(destination).not() && Files.exists(child).not())
        Files.createDirectories(checkNotNull(destination.parent))
        val runtime = NativeRoutingCpuEvidence.runtime()
        val identity = NativeRoutingCpuEvidence.identity()
        val connector = Bootstrap.virtualMachineManager().defaultConnector()
        val arguments = connector.defaultArguments()
        arguments.getValue("main").setValue("${NativeRoutingProbeTarget::class.java.name} \"$child\"")
        arguments.getValue("options").setValue("--add-opens=java.base/java.util.concurrent=ALL-UNNAMED -cp \"${System.getProperty("java.class.path")}\"")
        val vm = connector.launch(arguments)
        val output = Executors.newFixedThreadPool(2)
        val readers = listOf(vm.process().inputStream, vm.process().errorStream).map { stream -> output.submit<String> { stream.bufferedReader().use { it.readText() } } }
        var active: Row? = null
        try {
            check(vm.canGetMethodReturnValues()) { "Actual return-array evidence requires JDI method-return values" }
            val manager = vm.eventRequestManager()
            manager.createMethodEntryRequest().apply {
                addClassFilter(NativeRoutingProbeTarget::class.java.name)
                setSuspendPolicy(EventRequest.SUSPEND_ALL)
                enable()
            }
            val observations = listOf(
                "dev.s7a.strata.runtime.remote.RemotePacket\$Companion",
                "dev.s7a.strata.runtime.remote.RemoteFraming",
                "com.velocitypowered.api.event.connection.PluginMessageEvent",
            ).map { type -> manager.createMethodExitRequest().apply { addClassFilter(type)
                    setSuspendPolicy(EventRequest.SUSPEND_ALL) } } +
                listOf("dev.s7a.strata.runtime.remote.RemoteFrameInbox", NativeRoutingPlayer::class.java.name).map { type ->
                    manager.createMethodEntryRequest().apply { addClassFilter(type)
                    setSuspendPolicy(EventRequest.SUSPEND_ALL) }
                }
            val probes = mutableMapOf<List<String>, JsonObject>()
            var finished = false
            while (finished.not()) {
                val events = checkNotNull(vm.eventQueue().remove(60_000)) { "Native routing probe target stalled" }
                events.forEach { event ->
                    when (event) {
                        is MethodEntryEvent -> {
                            val type = event.method().declaringType().name()
                            if (type == NativeRoutingProbeTarget::class.java.name) {
                                when (event.method().name()) {
                                    "begin" -> {
                                        check(active == null)
                                        val values = event.thread().frame(0).getArgumentValues()
                                        val players = (values[0] as IntegerValue).value().toString()
                                        val key = listOf(players) + values.drop(1).map { (it as StringReference).value() }
                                        check(key !in probes)
                                        active = Row(key, event.thread().uniqueID())
                                        observations.forEach { it.enable() }
                                    }
                                    "end" -> {
                                        val row = checkNotNull(active)
                                        check(row.thread == event.thread().uniqueID())
                                        observations.forEach { it.disable() }
                                        val arrays = event.thread().frame(0).getArgumentValues().single() as ArrayReference
                                        probes[row.key] = row.complete(arrays)
                                        row.release()
                                        active = null
                                    }
                                }
                            } else {
                                active?.takeIf { it.thread == event.thread().uniqueID() }?.enter(event)
                            }
                        }
                        is MethodExitEvent -> active?.takeIf { it.thread == event.thread().uniqueID() }?.exit(event)
                        is VMDeathEvent, is VMDisconnectEvent -> finished = true
                    }
                }
                if (finished.not()) events.resume()
            }
            check(active == null && vm.process().waitFor(10, TimeUnit.SECONDS) && vm.process().exitValue() == 0)
            val logs = readers.map { it.get(10, TimeUnit.SECONDS) }
            val report = Files.newBufferedReader(child).use { JsonParser.parseReader(it).asJsonObject }
            check(report.get("runtime_metadata") == runtime)
            check(report.get("fixture_identity") == Gson().toJsonTree(identity))
            val rows = report.getAsJsonArray("rows")
            check(rows.size() == 14 * NativeRoutingWorkload.entries.size && probes.size == rows.size())
            rows.forEach { value ->
                val row = value.asJsonObject
                val key = listOf("players", "workload", "direction", "phase").map { row.get(it).asString }
                val measured = probes.getValue(key)
                if (NativeRoutingPhase.valueOf(row.get("phase").asString) == NativeRoutingPhase.Callback) {
                    check(measured.get("forwarded_packets").asLong == row.getAsJsonObject("work").get("forwarded_packets").asLong)
                    check(measured.get("forwarded_bytes").asLong == row.getAsJsonObject("work").get("forwarded_bytes").asLong)
                    check(measured.get("inbox_snapshot_packets").asLong == row.getAsJsonObject("work").get("owner_inbox_packets").asLong)
                    check(measured.get("inbox_snapshot_bytes").asLong == row.getAsJsonObject("work").get("owner_inbox_snapshot_bytes").asLong)
                }
                row.add("probes", measured)
            }
            report.addProperty("contract", "native-routing-return-arrays-v1")
            report.addProperty("status", "passed")
            report.addProperty("timing", "N/A: debugger suspension is untimed instrumentation")
            report.add("logs", Gson().toJsonTree(logs))
            PerformanceJson.writeNew(destination, report)
        } finally {
            try {
                active?.release()
            } catch (_: VMDisconnectedException) {
                // A failed/disconnected child no longer retains debugger-protected target arrays.
            }
            try {
                vm.dispose()
            } catch (_: VMDisconnectedException) {
                // Successful child completion already detached the debugger.
            }
            vm.process().destroy()
            output.shutdownNow()
            check(output.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    /**
     * One bounded interval's scalar counters and temporarily protected actual arrays; no row history retains mirrors.
     */
    private class Row(val key: List<String>, val thread: Long) {
        private val phase = NativeRoutingPhase.valueOf(key[3])
        private val getters = mutableMapOf<Long, Int>()
        private val offered = mutableMapOf<Long, Int>()
        private val retained = mutableListOf<ObjectReference>()
        private var decodedArrays = 0L
        private var decodedBytes = 0L
        private var metadataFrames = 0L
        private var assembledArrays = 0L
        private var assembledBytes = 0L
        private var forwardedPackets = 0L
        private var forwardedBytes = 0L
        private var inboxOffers = 0L
        private var endpointPackets = 0L
        private var endpointBytes = 0L

        /**
         * Observes real inbox offer arguments and exact arrays passed to the authenticated writer double.
         */
        fun enter(event: MethodEntryEvent) {
            val values = event.thread().frame(0).getArgumentValues()
            when (event.method().name()) {
                "offer" -> {
                    val array = values.first() as ArrayReference
                    inboxOffers++
                    if (0 < array.length()) offered[array.uniqueID()] = array.length()
                }
                "write" -> {
                    val arguments = values[1] as ArrayReference
                    val array = arguments.getValue(1) as ArrayReference
                    when (phase) {
                        NativeRoutingPhase.Callback -> {
                            check(getters[array.uniqueID()] == array.length()) { "Forwarded storage must be the actual event accessor result" }
                            forwardedPackets++
                            forwardedBytes += array.length()
                        }
                        NativeRoutingPhase.OwnerProcessing -> {
                            endpointPackets++
                            endpointBytes += array.length()
                        }
                        NativeRoutingPhase.PublicDecode -> error("Public decoder control cannot invoke an endpoint writer")
                    }
                }
            }
        }

        /**
         * Counts successful actual return arrays; exceptions allocate no detached public Frame at this outer boundary.
         */
        fun exit(event: MethodExitEvent) {
            val value = event.returnValue()
            val type = event.method().declaringType().name()
            when {
                type == "com.velocitypowered.api.event.connection.PluginMessageEvent" && event.method().name() == "getData" -> {
                    val array = value as ArrayReference
                    array.disableCollection()
                    retained.add(array)
                    check(getters.put(array.uniqueID(), array.length()) == null)
                }
                type == "dev.s7a.strata.runtime.remote.RemotePacket\$Companion" && event.method().name() == "decode" -> {
                    val packet = value as ObjectReference
                    if (packet.referenceType().name() == "dev.s7a.strata.runtime.remote.RemotePacket\$Frame") {
                        val array = packet.getValue(checkNotNull(packet.referenceType().fieldByName("bytes"))) as ArrayReference
                        decodedArrays++
                        decodedBytes += array.length()
                    }
                }
                type == "dev.s7a.strata.runtime.remote.RemotePacket\$Companion" && event.method().name() == "inspect" -> {
                    val packet = value as ObjectReference
                    if (packet.referenceType().name() == "dev.s7a.strata.runtime.remote.RemotePacketRoute\$Frame") {
                        val fields = packet.referenceType().allFields().filter { it.isStatic.not() }
                        check(fields.size == 1 && fields.single().typeName() == "dev.s7a.strata.runtime.remote.RemoteAddress")
                        metadataFrames++
                    }
                }
                type == "dev.s7a.strata.runtime.remote.RemoteFraming" && event.method().name() == "receive" && value is ArrayReference -> {
                    assembledArrays++
                    assembledBytes += value.length()
                }
            }
        }

        /**
         * Proves real proxy snapshot storage differs from the actual offered/accessor arrays and emits scalars only.
         */
        fun complete(snapshots: ArrayReference): JsonObject {
            val arrays = snapshots.values.map { it as ArrayReference }
            arrays.forEach { array ->
                check(array.uniqueID() !in offered && array.uniqueID() !in getters)
            }
            if (arrays.isNotEmpty()) {
                check(offered.keys.all { it in getters })
                check(arrays.size == offered.size && arrays.sumOf { it.length() } == offered.values.sum())
            }
            return JsonObject().apply {
                addProperty("decoded_frame_arrays", decodedArrays)
                addProperty("decoded_frame_bytes", decodedBytes)
                addProperty("routing_metadata_frames", metadataFrames)
                addProperty("event_data_arrays", getters.size)
                addProperty("event_data_bytes", getters.values.sumOf { it.toLong() })
                addProperty("forwarded_packets", forwardedPackets)
                addProperty("forwarded_bytes", forwardedBytes)
                addProperty("inbox_offers", inboxOffers)
                addProperty("inbox_snapshot_packets", arrays.size)
                addProperty("inbox_snapshot_bytes", arrays.sumOf { it.length().toLong() })
                addProperty("assembled_arrays", assembledArrays)
                addProperty("assembled_bytes", assembledBytes)
                addProperty("endpoint_output_packets", endpointPackets)
                addProperty("endpoint_output_bytes", endpointBytes)
            }
        }

        /**
         * Releases debugger protection at the terminal row boundary; no measured invocation holds these references.
         */
        fun release() {
            retained.forEach { it.enableCollection() }
            retained.clear()
            getters.clear()
            offered.clear()
        }
    }
}
