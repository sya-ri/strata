package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.jdi.Bootstrap
import com.sun.jdi.Method
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.ExceptionEvent
import com.sun.jdi.event.MethodExitEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.event.VMStartEvent
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.EventRequest
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * External untimed JDI observer that never instruments or rewrites a runtime archive.
 * Owns each launched worker, bounded stream capture and exact method-entry observations until terminal release.
 */
public object UnihexBoundsMethodTrace {
    private const val FONT = "dev.s7a.strata.runtime.minecraft.font."
    private const val WORKER = "dev.s7a.strata.quality.benchmark.UnihexBoundsTraceWorker"
    private val methods =
        mapOf(
            FONT + "MinecraftFontEngine" to listOf("glyph", "cachedGlyph", "resolveGlyph", "unihexGlyph", "readBitmapCell", "decodeBitmap"),
            FONT + "lwjgl.StbMinecraftFontFace" to listOf("glyph"),
            FONT + "lwjgl.FreeTypeMinecraftFontFace" to listOf("glyph"),
            "dev.s7a.strata.render.DrawImages" to listOf("createOwnedDrawImage"),
        )

    /**
     * Runs the complete pinned inventory and writes a receipt only after every actual worker succeeds.
     */
    @JvmStatic
    public fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Supply one pinned preflight request" }
        val requestPath = Path.of(arguments[0]).toAbsolutePath()
        val request = JsonParser.parseString(Files.readString(requestPath)).asJsonObject
        val output = Path.of(request.get("output").asString)
        val controls = request.getAsJsonArray("controls")
        val qualification =
            controls.size() == 7 &&
                controls[0]
                    .asJsonObject
                    .get("id")
                    .asString
                    .contentEquals(QualificationControl.QSingle.name)
        if (qualification) {
            val actual = controls.map { it.asJsonObject.get("id").asString }
            require(actual == QualificationControl.entries.map { it.name }) { "Retain all known controls and original TrueType, Bitmap and SnapshotLoad rows" }
        } else {
            val qualified = JsonParser.parseString(Files.readString(Path.of(request.getAsJsonObject("qualification").get("path").asString))).asJsonObject
            require(
                qualified.get("schema").asString.contentEquals("strata-unihex-method-trace-qualification-v1") &&
                    qualified.get("status").asString.contentEquals("passed") &&
                    qualified.get("controller_class_sha256").asString.contentEquals(classHash(UnihexBoundsMethodTrace::class.java)) &&
                    qualified.get("worker_class_sha256").asString.contentEquals(classHash(UnihexBoundsTraceWorker::class.java)),
            ) {
                "A current actual controller qualification is required before the whole trace"
            }
            require(controls.size() == 132) { "Retain the complete 132-row trace inventory" }
        }
        val results = JsonArray()
        controls.forEach { entry ->
            val control = entry.asJsonObject
            val id = control.get("id").asString
            val options = control.getAsJsonArray("arguments")
            require(options.size() == 1) { "Supply one exact compiled worker control" }
            val trace = observe(requestPath, request, output, id, options[0].asString)
            if (qualification) verifyQualification(QualificationControl.valueOf(id), trace.observations)
            results.add(
                JsonObject().apply {
                    addProperty("id", id)
                    addProperty("status", "passed")
                    add("observations", trace.observations)
                    add("origins", trace.origins)
                    add("artifacts", trace.artifacts)
                },
            )
            println("Unihex external trace completed $id ${trace.observations}")
        }
        writeNew(
            output.resolve("receipt.json"),
            JsonObject().apply {
                addProperty("schema", "strata-paired-jvm-preflight-receipt-v1")
                addProperty("status", "passed")
                addProperty("request_sha256", hash(requestPath))
                add("controls", results)
            },
        )
    }

    /**
     * Observes actual bytecode entry and returns between operation-thread println marker arguments.
     */
    private fun observe(
        requestPath: Path,
        request: JsonObject,
        output: Path,
        id: String,
        argument: String,
    ): Trace {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) { "Unsafe trace identity" }
        val directory = Files.createDirectory(output.resolve(id))
        val classLog = directory.resolve("class-load.log")
        val vm = launchWorker(requestPath, request, directory, argument)
        val process = vm.process()
        val stdout = drain(process.inputStream, directory.resolve("stdout.log"), System.out)
        val stderr = drain(process.errorStream, directory.resolve("stderr.log"), System.err)
        val trace = Trace(process.pid())
        try {
            armObserver(vm, trace)
            observeEvents(vm, trace, id)
            check(process.waitFor(10, TimeUnit.SECONDS)) { "Trace worker did not release naturally" }
            stdout.get(10, TimeUnit.SECONDS)
            stderr.get(10, TimeUnit.SECONDS)
            // The connector exposes pipes only after handshake; preserve the full startup log separately.
            mirrorOrigins(classLog, process.pid(), request.getAsJsonObject("required_classes"))
            trace.observations.addProperty("exit_code", process.exitValue())
            trace.observations.addProperty("complete_boundary", trace.begins == 1 && trace.ends == 1 && trace.active.not())
            check(process.exitValue() == 0 && trace.begins == 1 && trace.ends == 1 && trace.active.not()) { "Failed worker or incomplete external operation boundary" }
            request.getAsJsonObject("required_classes").keySet().forEach { name ->
                trace.origins.add(name, JsonObject().apply { addProperty("pid", process.pid()) })
            }
            return trace
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (process.waitFor(10, TimeUnit.SECONDS).not()) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            }
            writeNew(
                directory.resolve("events.json"),
                JsonObject().apply {
                    addProperty("pid", process.pid())
                    add("observations", trace.observations)
                    add("events", trace.events)
                },
            )
            listOf("stdout.log", "stderr.log", "class-load.log", "events.json").forEach { name ->
                val path = directory.resolve(name)
                trace.artifacts.add(
                    JsonObject().apply {
                        addProperty("path", output.relativize(path).toString().replace('\\', '/'))
                        addProperty("sha256", hash(path))
                    },
                )
            }
        }
    }

    /**
     * Launches the pinned suspended worker with finite VM caps and its origin declaration.
     */
    private fun launchWorker(
        requestPath: Path,
        request: JsonObject,
        directory: Path,
        argument: String,
    ): VirtualMachine {
        val classpath = request.getAsJsonObject("classpath").getAsJsonArray("entries").joinToString(File.pathSeparator) { it.asJsonObject.get("path").asString }
        val font = requireNotNull(System.getProperty("strata.performance.fontFixture")) { "Preserve the original TrueType control input" }
        val connector = Bootstrap.virtualMachineManager().defaultConnector()
        val options = connector.defaultArguments()
        options.getValue("home").setValue(System.getProperty("java.home"))
        options.getValue("main").setValue("$WORKER ${quote(requestPath.toString())} ${quote(argument)}")
        val classLog = directory.resolve("class-load.log")
        val relativeLog =
            Path
                .of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .relativize(classLog)
                .toString()
                .replace(File.separatorChar, '/')
        options.getValue("options").setValue("-Xmx768m -XX:MaxMetaspaceSize=384m --enable-native-access=ALL-UNNAMED ${quote("-Xlog:class+load=info:file=$relativeLog:pid,level,tags:filecount=0")} -Dstrata.performance.fontFixture=${quote(font)} -classpath ${quote(classpath)}")
        options.getValue("suspend").setValue("true")
        val vm = connector.launch(options)
        val origin =
            JsonObject().apply {
                addProperty("task", "external-unihex-trace:${directory.fileName}")
                addProperty("main", WORKER)
                addProperty("executable", Path.of(System.getProperty("java.home"), "bin", "java.exe").toString())
                addProperty("classpath", classpath)
            }
        println("paired.jvm.origin $origin")
        return vm
    }

    /**
     * Arms exact loaded methods while the worker remains suspended, then releases its startup event.
     */
    private fun armObserver(
        vm: VirtualMachine,
        trace: Trace,
    ) {
        val started = vm.eventQueue().remove(10000)
        check(started != null && started.any { it is VMStartEvent }) { "Missing actual JDI VM start" }
        check(vm.canGetMethodReturnValues()) { "JDI actual method return values are unavailable" }
        trace.observations.addProperty("can_get_method_return_values", vm.canGetMethodReturnValues())
        val filters = methods.keys + listOf(FONT + "FontHexGlyph", "java.io.PrintStream")
        filters.forEach { filter ->
            vm.eventRequestManager().createClassPrepareRequest().apply {
                addClassFilter(filter)
                setSuspendPolicy(EventRequest.SUSPEND_ALL)
                enable()
            }
            vm.classesByName(filter).filter { it.isPrepared }.forEach { trace.prepareType(vm, it) }
            if (methods.containsKey(filter)) {
                val returned =
                    vm.eventRequestManager().createMethodExitRequest().apply {
                        addClassFilter(filter)
                        setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                    }
                trace.operationRequests.add(returned)
            }
        }
        val failureRequest =
            vm.eventRequestManager().createExceptionRequest(null, true, true).apply {
                addClassFilter(FONT + "MinecraftFontEngine")
                setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
            }
        trace.operationRequests.add(failureRequest)
        started.resume()
    }

    /**
     * Dispatches suspended native events until the actual worker VM disconnects naturally.
     */
    private fun observeEvents(
        vm: VirtualMachine,
        trace: Trace,
        id: String,
    ) {
        var disconnected = false
        while (disconnected.not()) {
            val events = vm.eventQueue().remove(1000) ?: continue
            events.forEach { event ->
                when (event) {
                    is VMDisconnectEvent -> disconnected = true
                    is ClassPrepareEvent -> trace.prepareType(vm, event.referenceType())
                    is BreakpointEvent -> trace.enter(event, id)
                    is MethodExitEvent -> trace.exit(event)
                    is ExceptionEvent -> trace.failure(event)
                    else -> Unit
                }
            }
            if (disconnected.not()) events.resume()
        }
    }

    /**
     * Mirrors unique actual PID-qualified class origins without expanding the bounded shared console.
     */
    private fun mirrorOrigins(
        classLog: Path,
        pid: Long,
        required: JsonObject,
    ) {
        check(Files.size(classLog) <= 64L * 1024 * 1024) { "Trace class-load output overflow" }
        required.keySet().forEach { name ->
            val loaded = Pattern.compile("^\\[" + pid + "\\]\\[info *\\]\\[class,load *\\] " + Pattern.quote(name) + " source: [^\\r\\n]+")
            val matches = Files.lines(classLog, StandardCharsets.UTF_8).use { lines -> lines.filter { loaded.matcher(it).matches() }.toList() }
            check(matches.size == 1) { "Missing or ambiguous actual worker class origin: $name" }
            println(matches.single())
        }
    }

    /**
     * Owns the suspended worker's observations and separates absence from successful raster construction.
     */
    private class Trace(
        pid: Long,
    ) {
        val observations = JsonObject()
        val origins = JsonObject()
        val artifacts = JsonArray()
        val events = JsonArray()
        val operationRequests = mutableListOf<EventRequest>()
        var active = false
            private set
        var begins = 0
            private set
        var ends = 0
            private set
        private val preparedTypes = mutableSetOf<String>()
        private var boundsPrepared = false
        private var thread = 0L

        init {
            observations.addProperty("pid", pid)
            observations.add("method_entry_breakpoints", JsonArray())
            listOf("natural_bounds", "public_glyph_requests", "cached_glyph_requests", "provider_cache_misses", "unihex_raster_attempts", "unihex_absent_results", "unihex_constructed_rasters", "bitmap_cell_misses", "bitmap_decodes", "native_raster_requests", "owned_image_factory_entries", "expected_failures", "engine_exception_events").forEach { observations.addProperty(it, 0) }
        }

        /**
         * Installs selected concrete overloads at their actual first bytecode while class preparation is suspended.
         */
        fun prepareType(
            vm: VirtualMachine,
            type: ReferenceType,
        ) {
            val name = type.name()
            check(preparedTypes.add(name)) { "Repeated actual trace type: $name" }
            val selected = selectedMethods(type)
            val signatures = mutableSetOf<String>()
            selected.forEach { method ->
                val entry = method.locationOfCodeIndex(0)
                check(method.declaringType() == type && method.isNative.not() && method.isAbstract.not() && entry != null && entry.codeIndex() == 0L && entry.method() == method && signatures.add(method.name() + method.signature())) { "Actual traced method has no unique executable entry at code index zero" }
                vm.eventRequestManager().createBreakpointRequest(entry).apply {
                    setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                    enable()
                }
                val definition =
                    JsonObject().apply {
                        addProperty("class", name)
                        addProperty("method", method.name())
                        addProperty("signature", method.signature())
                        addProperty("code_index", entry.codeIndex())
                    }
                observations.getAsJsonArray("method_entry_breakpoints").add(definition)
                if (name.contentEquals(FONT + "FontHexGlyph")) {
                    boundsPrepared = true
                    observations.add("bounds_entry_breakpoint", definition)
                }
            }
        }

        private fun selectedMethods(type: ReferenceType): List<Method> {
            val name = type.name()
            return when {
                name.contentEquals(FONT + "FontHexGlyph") -> {
                    type.methodsByName("bounds", "()Lkotlin/ranges/IntRange;").also { check(it.size == 1) { "Missing or ambiguous actual bounds method" } }
                }

                name.contentEquals("java.io.PrintStream") -> {
                    listOf("(Ljava/lang/String;)V", "(Ljava/lang/Object;)V").map { signature ->
                        type.methodsByName("println", signature).also { check(it.size == 1) { "Missing or ambiguous actual marker method: $signature" } }.single()
                    }
                }

                else -> {
                    check(methods.containsKey(name)) { "Unexpected actual trace type: $name" }
                    methods.getValue(name).flatMap { method -> type.methodsByName(method).also { check(it.isNotEmpty()) { "Missing actual traced method: $method" } } }
                }
            }
        }

        /**
         * Counts real bounds hits only at bytecode zero on the active operation thread.
         */
        private fun boundsEntry(event: BreakpointEvent) {
            val location = event.location()
            val method = location.method()
            check(boundsPrepared && location.codeIndex() == 0L && method.declaringType().name().contentEquals(FONT + "FontHexGlyph") && method.name().contentEquals("bounds") && method.signature().contentEquals("()Lkotlin/ranges/IntRange;")) { "Unexpected actual bounds breakpoint location" }
            if (active.not() || thread != event.thread().uniqueID()) return
            increment("natural_bounds")
            record("entry_breakpoint", method, null)
            events[events.size() - 1].asJsonObject.addProperty("code_index", location.codeIndex())
        }

        fun enter(
            event: BreakpointEvent,
            id: String,
        ) {
            val location = event.location()
            val method = location.method()
            val type = method.declaringType().name()
            val request = event.request()
            check(location.codeIndex() == 0L && preparedTypes.contains(type) && request is BreakpointRequest && request.location() == location) { "Unexpected actual entry breakpoint" }
            if (type.contentEquals(FONT + "FontHexGlyph")) {
                boundsEntry(event)
                return
            }
            if (type.contentEquals("java.io.PrintStream")) {
                marker(event, id)
                return
            }
            if (active.not() || thread != event.thread().uniqueID() || methods.getValue(type).contains(method.name()).not()) return
            record("entry_breakpoint", method, null)
            events[events.size() - 1].asJsonObject.addProperty("code_index", location.codeIndex())
            when {
                type.contentEquals(FONT + "MinecraftFontEngine") -> increment(EngineMethod.entries.single { it.nativeName.contentEquals(method.name()) }.observation)
                type.contentEquals("dev.s7a.strata.render.DrawImages") -> increment("owned_image_factory_entries")
                else -> increment("native_raster_requests")
            }
        }

        /**
         * Decodes actual worker String-reference markers while the matching println thread is suspended.
         */
        private fun marker(
            event: BreakpointEvent,
            id: String,
        ) {
            val location = event.location()
            val method = location.method()
            val marker = readMarker(event) ?: return
            when {
                marker.startsWith("Unihex operation begin $id ") -> {
                    check(boundsPrepared) { "Actual bounds entry was not observed before the operation boundary" }
                    check(begins == 0 && ends == 0) { "Repeated operation begin" }
                    begins++
                    active = true
                    thread = event.thread().uniqueID()
                    // Broad notifications remain disabled during uncounted setup and teardown.
                    operationRequests.forEach { it.enable() }
                    observations.addProperty("begin_marker_code_index", location.codeIndex())
                    observations.addProperty("begin_marker", marker)
                    observations.addProperty("begin_marker_signature", method.signature())
                    record("begin", method, marker)
                }

                marker.startsWith("Unihex operation end $id ") -> {
                    check(active && thread == event.thread().uniqueID()) { "Unowned operation end" }
                    active = false
                    operationRequests.forEach { it.disable() }
                    ends++
                    observations.addProperty("end_marker_code_index", location.codeIndex())
                    observations.addProperty("end_marker", marker)
                    observations.addProperty("end_marker_signature", method.signature())
                    record("end", method, marker)
                }

                active && marker.startsWith("Unihex operation failure $id ") -> {
                    increment("expected_failures")
                    observations.addProperty("failure_marker", marker)
                }

                marker.startsWith("Unihex frame work $id ") -> {
                    check(begins == 1 && ends == 1 && active.not()) { "Frame work escaped the complete operation boundary" }
                    observations.add("frame_work", JsonParser.parseString(marker.substring("Unihex frame work $id ".length)).asJsonObject)
                }
            }
        }

        private fun readMarker(event: BreakpointEvent): String? {
            val method = event.location().method()
            if (method.name().contentEquals("println").not() || listOf("(Ljava/lang/String;)V", "(Ljava/lang/Object;)V").contains(method.signature()).not()) return null
            if (1 < event.thread().frameCount()) {
                val caller =
                    event
                        .thread()
                        .frame(1)
                        .location()
                        .method()
                if (caller.declaringType().name().contentEquals("java.io.PrintStream") && caller.name().contentEquals("println")) return null
            }
            val value = event.thread().frame(0).getArgumentValues()[0] as? StringReference ?: return null
            return value.value()
        }

        fun exit(event: MethodExitEvent) {
            val method = event.method()
            val type = method.declaringType().name()
            if (active.not() || thread != event.thread().uniqueID() || methods.getValue(type).contains(method.name()).not()) return
            val returned = event.returnValue()
            record("return", method, returned?.type()?.name() ?: "null")
            if (type.contentEquals(FONT + "MinecraftFontEngine") && method.name().contentEquals("unihexGlyph")) increment(if (returned == null) "unihex_absent_results" else "unihex_constructed_rasters")
        }

        /**
         * Records thrown engine exceptions independently of the caught-failure marker.
         */
        fun failure(failure: ExceptionEvent) {
            if (active.not() || thread != failure.thread().uniqueID()) return
            increment("engine_exception_events")
            record("exception", failure.location().method(), failure.exception().referenceType().name())
            observations.addProperty("exception_catch_location", failure.catchLocation()?.toString())
        }

        private fun increment(name: String) = observations.addProperty(name, observations.get(name).asLong + 1)

        private fun record(
            kind: String,
            method: Method,
            returned: String?,
        ) {
            check(events.size() < 16_384) { "External method trace overflow" }
            events.add(
                JsonObject().apply {
                    addProperty("kind", kind)
                    addProperty("class", method.declaringType().name())
                    addProperty("method", method.name())
                    addProperty("signature", method.signature())
                    addProperty("thread", thread)
                    if (returned != null) addProperty("returned", returned)
                },
            )
        }
    }

    /**
     * Decodes the external engine method boundary into its measured observation.
     */
    private enum class EngineMethod(
        val nativeName: String,
        val observation: String,
    ) {
        Glyph("glyph", "public_glyph_requests"),
        Cached("cachedGlyph", "cached_glyph_requests"),
        Resolve("resolveGlyph", "provider_cache_misses"),
        Unihex("unihexGlyph", "unihex_raster_attempts"),
        BitmapCell("readBitmapCell", "bitmap_cell_misses"),
        BitmapDecode("decodeBitmap", "bitmap_decodes"),
    }

    /**
     * Known independently observable qualification controls, including the original raster controls.
     */
    private enum class QualificationControl(
        val bounds: Long,
    ) {
        QSingle(1),
        QMultiple(3),
        QZero(0),
        QFailure(0),
        R127(0),
        R128(0),
        R129(0),
    }

    /**
     * Checks real bounds multiplicity and terminal engine failure before admitting qualification.
     */
    private fun verifyQualification(
        control: QualificationControl,
        observed: JsonObject,
    ) {
        val failures = if (control == QualificationControl.QFailure) 1L else 0L
        check(observed.get("natural_bounds").asLong == control.bounds && observed.get("expected_failures").asLong == failures && observed.get("engine_exception_events").asLong == failures) { "Independent trace qualification failed: $control" }
    }
}

/**
 * Retains complete bounded child streams concurrently; an I/O failure fails the controller.
 */
private fun drain(
    source: InputStream,
    path: Path,
    console: OutputStream,
): CompletableFuture<Unit> =
    CompletableFuture.supplyAsync {
        runCatching {
            source.use { input ->
                Files.newOutputStream(path, StandardOpenOption.CREATE_NEW).use { retained ->
                    val buffer = ByteArray(8192)
                    var bytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        bytes += count
                        check(bytes <= 64L * 1024 * 1024) { "Trace worker output overflow" }
                        retained.write(buffer, 0, count)
                        console.write(buffer, 0, count)
                        console.flush()
                    }
                }
            }
        }.getOrElse { failure ->
            if (failure is Exception) throw IllegalStateException("Could not retain trace worker stream", failure)
            throw failure
        }
    }

/**
 * Quotes launching-connector argument syntax and rejects ambiguous embedded quotes or lines.
 */
private fun quote(value: String): String {
    require(listOf('"', '\n', '\r').none { value.contains(it) }) { "Unrepresentable connector argument" }
    return "\"$value\""
}

/**
 * Hashes actual loaded controller bytes rather than trusting source declarations.
 */
private fun classHash(type: Class<*>): String =
    checkNotNull(type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")) { "Missing actual compiled trace class" }.use { bytes ->
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes()))
    }

/**
 * Hashes retained artifacts independently of the shared Python verifier.
 */
private fun hash(path: Path): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))

/**
 * Preserves fresh immutable evidence without replacing an earlier attempt.
 */
private fun writeNew(
    path: Path,
    value: JsonObject,
) {
    Files.writeString(path, value.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
}
