package dev.s7a.strata.quality.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Method;
import com.sun.jdi.StringReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.MethodExitRequest;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** External untimed JDI observer; it never instruments or rewrites a runtime archive. */
public final class UnihexBoundsMethodTrace {
    private static final String FONT = "dev.s7a.strata.runtime.minecraft.font.";
    private static final String WORKER = "dev.s7a.strata.quality.benchmark.UnihexBoundsTraceWorker";
    private static final Map<String, List<String>> METHODS = Map.of(
        FONT + "FontHexGlyph", List.of("bounds"),
        FONT + "MinecraftFontEngine", List.of("glyph", "cachedGlyph", "resolveGlyph", "unihexGlyph", "readBitmapCell", "decodeBitmap"),
        FONT + "lwjgl.StbMinecraftFontFace", List.of("glyph"),
        FONT + "lwjgl.FreeTypeMinecraftFontFace", List.of("glyph"),
        "dev.s7a.strata.render.DrawImages", List.of("createOwnedDrawImage")
    );

    private UnihexBoundsMethodTrace() {}

    /** Runs exactly the pinned control inventory and writes a receipt only after every real worker succeeds. */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Supply one pinned preflight request");
        Path requestPath = Path.of(arguments[0]).toAbsolutePath();
        JsonObject request = JsonParser.parseString(Files.readString(requestPath)).getAsJsonObject();
        Path output = Path.of(request.get("output").getAsString());
        JsonArray controls = request.getAsJsonArray("controls");
        boolean qualification = controls.size() == 4 && controls.get(0).getAsJsonObject().get("id").getAsString().equals("QSingle");
        if (qualification == false) {
            JsonObject qualified = JsonParser.parseString(Files.readString(Path.of(request.getAsJsonObject("qualification").get("path").getAsString()))).getAsJsonObject();
            if (qualified.get("schema").getAsString().equals("strata-unihex-method-trace-qualification-v1") == false
                || qualified.get("status").getAsString().equals("passed") == false
                || qualified.get("controller_class_sha256").getAsString().equals(classHash(UnihexBoundsMethodTrace.class)) == false
                || qualified.get("worker_class_sha256").getAsString().equals(classHash(UnihexBoundsTraceWorker.class)) == false)
                throw new IllegalArgumentException("A current actual controller qualification is required before the whole trace");
            if (controls.size() != 132) throw new IllegalArgumentException("Retain the complete 132-row trace inventory");
        }
        JsonArray results = new JsonArray();
        for (JsonElement entry : controls) {
            JsonObject control = entry.getAsJsonObject();
            String id = control.get("id").getAsString();
            JsonArray options = control.getAsJsonArray("arguments");
            if (options.size() != 1) throw new IllegalArgumentException("Supply one row or independently defined qualification identity");
            Trace trace = observe(requestPath, request, output, id, options.get(0).getAsString());
            if (qualification) verifyQualification(id, trace.observations);
            JsonObject result = new JsonObject();
            result.addProperty("id", id);
            result.addProperty("status", "passed");
            result.add("observations", trace.observations);
            result.add("origins", trace.origins);
            result.add("artifacts", trace.artifacts);
            results.add(result);
            System.out.println("Unihex external trace completed " + id + " " + trace.observations);
        }
        JsonObject receipt = new JsonObject();
        receipt.addProperty("schema", "strata-paired-jvm-preflight-receipt-v1");
        receipt.addProperty("status", "passed");
        receipt.addProperty("request_sha256", hash(requestPath));
        receipt.add("controls", results);
        writeNew(output.resolve("receipt.json"), receipt);
    }

    /** Observes actual method entry/return events on the operation thread between actual println(String) arguments. */
    private static Trace observe(Path requestPath, JsonObject request, Path output, String id, String argument) throws Exception {
        if (id.matches("[A-Za-z0-9][A-Za-z0-9_.-]*") == false) throw new IllegalArgumentException("Unsafe trace identity");
        Path directory = output.resolve(id);
        Files.createDirectory(directory);
        List<String> paths = new ArrayList<>();
        for (JsonElement entry : request.getAsJsonObject("classpath").getAsJsonArray("entries")) paths.add(entry.getAsJsonObject().get("path").getAsString());
        String classpath = String.join(System.getProperty("path.separator"), paths);
        String font = System.getProperty("strata.performance.fontFixture");
        if (font == null) throw new IllegalArgumentException("Preserve the original TrueType control input");
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> options = connector.defaultArguments();
        options.get("home").setValue(System.getProperty("java.home"));
        options.get("main").setValue(WORKER + " " + quote(requestPath.toString()) + " " + quote(argument));
        Path classLog = directory.resolve("class-load.log");
        String relativeLog = Path.of(System.getProperty("user.dir")).toAbsolutePath().relativize(classLog).toString().replace(File.separatorChar, '/');
        options.get("options").setValue("-Xmx768m -XX:MaxMetaspaceSize=384m --enable-native-access=ALL-UNNAMED " + quote("-Xlog:class+load=info:file=" + relativeLog + ":pid,level,tags:filecount=0") + " -Dstrata.performance.fontFixture=" + quote(font) + " -classpath " + quote(classpath));
        options.get("suspend").setValue("true");
        VirtualMachine vm = connector.launch(options);
        Process process = vm.process();
        JsonObject origin = new JsonObject();
        origin.addProperty("task", "external-unihex-trace:" + id);
        origin.addProperty("main", WORKER);
        origin.addProperty("executable", Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
        origin.addProperty("classpath", classpath);
        System.out.println("paired.jvm.origin " + origin);
        CompletableFuture<Void> stdout = drain(process.getInputStream(), directory.resolve("stdout.log"), System.out);
        CompletableFuture<Void> stderr = drain(process.getErrorStream(), directory.resolve("stderr.log"), System.err);
        Trace trace = new Trace(process.pid());
        try {
            EventSet started = vm.eventQueue().remove(10000);
            if (started == null || started.stream().noneMatch(event -> event instanceof VMStartEvent)) throw new IllegalStateException("Missing actual JDI VM start" );
            if (vm.canGetMethodReturnValues() == false) throw new IllegalStateException("JDI actual method return values are unavailable");
            trace.observations.addProperty("can_get_method_return_values", vm.canGetMethodReturnValues());
            List<String> filters = new ArrayList<>(METHODS.keySet());
            filters.add("java.io.PrintStream");
            for (String filter : filters) {
                MethodEntryRequest entered = vm.eventRequestManager().createMethodEntryRequest();
                entered.addClassFilter(filter);
                entered.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                entered.enable();
                if (filter.equals("java.io.PrintStream") == false && filter.equals(FONT + "FontHexGlyph") == false) {
                    MethodExitRequest returned = vm.eventRequestManager().createMethodExitRequest();
                    returned.addClassFilter(filter);
                    returned.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    returned.enable();
                }
            }
            ExceptionRequest failureRequest = vm.eventRequestManager().createExceptionRequest(null, true, true);
            failureRequest.addClassFilter(FONT + "MinecraftFontEngine");
            failureRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            failureRequest.enable();
            started.resume();
            boolean disconnected = false;
            while (disconnected == false) {
                EventSet events = vm.eventQueue().remove(1000);
                if (events == null) continue;
                for (Event event : events) {
                    if (event instanceof VMDisconnectEvent) disconnected = true;
                    else if (event instanceof MethodEntryEvent entered) trace.enter(entered, id);
                    else if (event instanceof MethodExitEvent returned) trace.exit(returned);
                    else if (event instanceof ExceptionEvent failure) trace.failure(failure);
                }
                if (disconnected == false) events.resume();
            }
            if (process.waitFor(10, TimeUnit.SECONDS) == false) throw new IllegalStateException("Trace worker did not release naturally");
            stdout.get(10, TimeUnit.SECONDS);
            stderr.get(10, TimeUnit.SECONDS);
            // Startup class logs use a file because the launching connector exposes pipes only after the JDWP handshake.
            // Retain exact PID-qualified lines in the shared console after natural process release.
            try (InputStream classes = Files.newInputStream(classLog)) { classes.transferTo(System.out); }
            trace.observations.addProperty("exit_code", process.exitValue());
            trace.observations.addProperty("complete_boundary", trace.begins == 1 && trace.ends == 1 && trace.active == false);
            if (process.exitValue() != 0 || trace.begins != 1 || trace.ends != 1 || trace.active) throw new IllegalStateException("Failed worker or incomplete external operation boundary");
            for (String name : request.getAsJsonObject("required_classes").keySet()) {
                JsonObject actual = new JsonObject();
                actual.addProperty("pid", process.pid());
                trace.origins.add(name, actual);
            }
            return trace;
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (process.waitFor(10, TimeUnit.SECONDS) == false) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            JsonObject raw = new JsonObject();
            raw.addProperty("pid", process.pid());
            raw.add("observations", trace.observations);
            raw.add("events", trace.events);
            writeNew(directory.resolve("events.json"), raw);
            for (String name : List.of("stdout.log", "stderr.log", "class-load.log", "events.json")) {
                Path path = directory.resolve(name);
                JsonObject artifact = new JsonObject();
                artifact.addProperty("path", output.relativize(path).toString().replace('\\', '/'));
                artifact.addProperty("sha256", hash(path));
                trace.artifacts.add(artifact);
            }
        }
    }

    /** Captures every selected real method event and separates absence from successful construction. */
    private static final class Trace {
        private final JsonObject observations = new JsonObject();
        private final JsonObject origins = new JsonObject();
        private final JsonArray artifacts = new JsonArray();
        private final JsonArray events = new JsonArray();
        private boolean active;
        private long thread;
        private int begins;
        private int ends;

        private Trace(long pid) {
            observations.addProperty("pid", pid);
            for (String name : List.of("natural_bounds", "public_glyph_requests", "cached_glyph_requests", "provider_cache_misses", "unihex_raster_attempts", "unihex_absent_results", "unihex_constructed_rasters", "bitmap_cell_misses", "bitmap_decodes", "native_raster_requests", "owned_image_factory_entries", "expected_failures", "engine_exception_events")) observations.addProperty(name, 0);
        }

        private void enter(MethodEntryEvent event, String id) throws Exception {
            Method method = event.method();
            String type = method.declaringType().name();
            if (type.equals("java.io.PrintStream")) {
                if (method.name().equals("println") && (method.signature().equals("(Ljava/lang/String;)V") || method.signature().equals("(Ljava/lang/Object;)V"))) {
                    if (1 < event.thread().frameCount()) {
                        Method caller = event.thread().frame(1).location().method();
                        if (caller.declaringType().name().equals("java.io.PrintStream") && caller.name().equals("println")) return;
                    }
                    Object value = event.thread().frame(0).getArgumentValues().get(0);
                    if ((value instanceof StringReference) == false) return;
                    String marker = ((StringReference) value).value();
                    if (marker.startsWith("Unihex operation begin " + id + " ")) {
                        if (begins != 0 || ends != 0) throw new IllegalStateException("Repeated operation begin");
                        begins++;
                        active = true;
                        thread = event.thread().uniqueID();
                        observations.addProperty("begin_marker", marker);
                        observations.addProperty("begin_marker_signature", method.signature());
                        record("begin", method, marker);
                    } else if (marker.startsWith("Unihex operation end " + id + " ")) {
                        if (active == false || thread != event.thread().uniqueID()) throw new IllegalStateException("Unowned operation end");
                        active = false;
                        ends++;
                        observations.addProperty("end_marker", marker);
                        observations.addProperty("end_marker_signature", method.signature());
                        record("end", method, marker);
                    } else if (active && marker.startsWith("Unihex operation failure " + id + " ")) {
                        increment("expected_failures");
                        observations.addProperty("failure_marker", marker);
                    } else if (marker.startsWith("Unihex frame work " + id + " ")) {
                        if (begins != 1 || ends != 1 || active) throw new IllegalStateException("Frame work escaped the complete operation boundary");
                        observations.add("frame_work", JsonParser.parseString(marker.substring(("Unihex frame work " + id + " ").length())).getAsJsonObject());
                    }
                }
                return;
            }
            if (active == false || thread != event.thread().uniqueID() || METHODS.get(type).contains(method.name()) == false) return;
            record("entry", method, null);
            if (type.equals(FONT + "FontHexGlyph")) increment("natural_bounds");
            else if (type.equals(FONT + "MinecraftFontEngine")) {
                switch (method.name()) {
                    case "glyph" -> increment("public_glyph_requests");
                    case "cachedGlyph" -> increment("cached_glyph_requests");
                    case "resolveGlyph" -> increment("provider_cache_misses");
                    case "unihexGlyph" -> increment("unihex_raster_attempts");
                    case "readBitmapCell" -> increment("bitmap_cell_misses");
                    case "decodeBitmap" -> increment("bitmap_decodes");
                    default -> throw new IllegalStateException("Unclassified external engine method");
                }
            } else if (type.equals("dev.s7a.strata.render.DrawImages")) increment("owned_image_factory_entries");
            else increment("native_raster_requests");
        }

        private void exit(MethodExitEvent event) {
            Method method = event.method();
            String type = method.declaringType().name();
            if (active == false || thread != event.thread().uniqueID() || METHODS.get(type).contains(method.name()) == false) return;
            String result = event.returnValue() == null ? "null" : event.returnValue().type().name();
            record("return", method, result);
            if (type.equals(FONT + "MinecraftFontEngine") && method.name().equals("unihexGlyph")) increment(event.returnValue() == null ? "unihex_absent_results" : "unihex_constructed_rasters");
        }

        /** Records a real thrown engine exception independently of the worker's caught-failure marker. */
        private void failure(ExceptionEvent failure) {
            if (active == false || thread != failure.thread().uniqueID()) return;
            increment("engine_exception_events");
            record("exception", failure.location().method(), failure.exception().referenceType().name());
            observations.addProperty("exception_catch_location", failure.catchLocation() == null ? null : failure.catchLocation().toString());
        }

        private void increment(String name) { observations.addProperty(name, observations.get(name).getAsLong() + 1); }

        private void record(String kind, Method method, String returned) {
            if (16_384 <= events.size()) throw new IllegalStateException("External method trace overflow");
            JsonObject event = new JsonObject();
            event.addProperty("kind", kind);
            event.addProperty("class", method.declaringType().name());
            event.addProperty("method", method.name());
            event.addProperty("signature", method.signature());
            event.addProperty("thread", thread);
            if (returned != null) event.addProperty("returned", returned);
            events.add(event);
        }
    }

    /** Checks known actual bounds-entry multiplicities and a terminal engine failure before qualification is admitted. */
    private static void verifyQualification(String id, JsonObject observed) {
        long expected = switch (id) { case "QSingle" -> 1; case "QMultiple" -> 3; case "QZero", "QFailure" -> 0; default -> throw new IllegalArgumentException("Unknown qualification control"); };
        if (observed.get("natural_bounds").getAsLong() != expected
            || observed.get("expected_failures").getAsLong() != (id.equals("QFailure") ? 1 : 0)
            || observed.get("engine_exception_events").getAsLong() != (id.equals("QFailure") ? 1 : 0)) throw new IllegalStateException("Independent trace qualification failed: " + id);
    }

    /** Drains child streams concurrently and retains their complete bytes; an I/O failure fails the controller. */
    private static CompletableFuture<Void> drain(InputStream source, Path path, OutputStream console) {
        return CompletableFuture.runAsync(() -> {
            try (source; OutputStream retained = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192];
                long bytes = 0;
                int count;
                while ((count = source.read(buffer)) != -1) {
                    bytes += count;
                    if (64L * 1024 * 1024 < bytes) throw new IllegalStateException("Trace worker output overflow");
                    retained.write(buffer, 0, count);
                    console.write(buffer, 0, count);
                    console.flush();
                }
            } catch (Exception failure) { throw new IllegalStateException("Could not retain trace worker stream", failure); }
        });
    }

    /** Quotes the documented launching connector argument syntax, rejecting ambiguous embedded quotes. */
    private static String quote(String value) {
        if (value.contains("\"") || value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("Unrepresentable connector argument");
        return "\"" + value + "\"";
    }
    /** Hashes actual loaded controller bytes for qualification rather than trusting source declarations. */
    private static String classHash(Class<?> type) throws Exception {
        try (InputStream bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (bytes == null) throw new IllegalStateException("Missing actual compiled trace class");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes()));
        }
    }

    /** Hashes retained artifacts independently from the shared Python verifier. */
    private static String hash(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }

    /** Preserves fresh immutable evidence without replacing a prior attempt. */
    private static void writeNew(Path path, JsonObject value) throws Exception { Files.writeString(path, value.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); }
}
