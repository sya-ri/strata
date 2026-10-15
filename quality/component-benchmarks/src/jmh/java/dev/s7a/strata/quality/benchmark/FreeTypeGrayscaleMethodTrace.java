package dev.s7a.strata.quality.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.MethodExitRequest;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.lwjgl.util.freetype.FreeType;

/** External untimed entry/return observer for the complete original grayscale corpus; runtime bytes are unchanged. */
public final class FreeTypeGrayscaleMethodTrace {
    private static final String WORKER = "dev.s7a.strata.quality.benchmark.FreeTypeGrayscaleTraceWorker";
    private static final String FACE = "dev.s7a.strata.runtime.minecraft.font.lwjgl.FreeTypeMinecraftFontFace";
    private static final String NATIVE = "org.lwjgl.util.freetype.FreeType";
    private static final String BITMAP = "org.lwjgl.util.freetype.FT_Bitmap";
    private static final String BUFFER = "java.nio.DirectByteBuffer";
    private static final String PRINT = "java.io.PrintStream";
    private static final String LOAD = "(Lorg/lwjgl/util/freetype/FT_Face;II)I";
    private static final List<String> TYPES = List.of(FACE, NATIVE, BITMAP, BUFFER, PRINT);
    private static final List<Integer> RASTER = List.of(FreeType.FT_LOAD_NO_BITMAP | FreeType.FT_LOAD_BITMAP_METRICS_ONLY,
        FreeType.FT_LOAD_BITMAP_METRICS_ONLY, FreeType.FT_LOAD_RENDER);

    private FreeTypeGrayscaleMethodTrace() {}

    /** Executes independently counted qualification first, then every original row before emitting the shared receipt. */
    public static void main(String[] arguments) throws Exception {
        require(arguments.length == 1, "Supply one pinned preflight request");
        Path requestPath = Path.of(arguments[0]).toAbsolutePath();
        JsonObject request = JsonParser.parseString(Files.readString(requestPath)).getAsJsonObject();
        Path output = Path.of(request.get("output").getAsString());
        JsonObject specification = JsonParser.parseString(Files.readString(Path.of(request.getAsJsonObject("qualification").get("path").getAsString()))).getAsJsonObject();
        require(specification.get("schema").getAsString().equals("strata-freetype-trace-independent-controls-v1"), "Missing independent qualification definition");
        require(specification.get("prior_execution_claimed").getAsBoolean() == false, "A specification cannot claim an actual prior qualification");
        List<String> ids = new ArrayList<>();
        for (JsonElement item : request.getAsJsonArray("controls")) {
            JsonObject control = item.getAsJsonObject();
            String id = control.get("id").getAsString();
            require(control.getAsJsonArray("arguments").size() == 1 && control.getAsJsonArray("arguments").get(0).getAsString().equals(id), "Trace argument must retain the original row identity");
            ids.add(id);
        }
        require(ids.equals(inventory()), "Retain all forty original rows in order");
        JsonArray qualification = new JsonArray();
        for (String id : List.of("QZero", "QOne", "QThree", "QMissingEnd")) {
            Trace observed = observe(requestPath, request, output, id);
            int expected = id.equals("QOne") ? 1 : id.equals("QThree") ? 3 : 0;
            verifyQualificationCounts(observed, expected);
            for (int index = 0; index < expected; index++) {
                require(observed.qualificationReads.get(index).getAsInt() == index, "Independent source read index changed");
                require(observed.loads.get(index).getAsInt() == FreeType.FT_LOAD_BITMAP_METRICS_ONLY, "Independent actual native-call argument changed");
            }
            require(observed.begins == 1 && observed.ends == (id.equals("QMissingEnd") ? 0 : 1), "Independent missing-boundary negative failed");
            require(observed.conversions.isEmpty() && observed.nativeReturns == expected, "Independent calls were confused with runtime conversion");
            if (id.equals("QMissingEnd")) expectRejected(() -> requireCompleteBoundary(observed));
            else requireCompleteBoundary(observed);
            if (id.equals("QThree")) expectRejected(() -> verifyQualificationCounts(observed, 1));
            JsonObject actual = observed.observations();
            actual.addProperty("id", id);
            actual.addProperty("expected_boundary_rejection", id.equals("QMissingEnd"));
            actual.addProperty("actual_wrong_count_rejected", id.equals("QThree"));
            qualification.add(actual);
        }
        JsonObject qualified = new JsonObject();
        qualified.addProperty("schema", "strata-freetype-method-trace-qualification-v1");
        qualified.addProperty("status", "passed");
        qualified.addProperty("controller_class_sha256", classHash(FreeTypeGrayscaleMethodTrace.class));
        qualified.addProperty("worker_class_sha256", classHash(FreeTypeGrayscaleTraceWorker.class));
        qualified.add("actual_controls", qualification);
        writeNew(output.resolve("actual-qualification.json"), qualified);
        JsonArray results = new JsonArray();
        for (String id : ids) {
            Trace observed = observe(requestPath, request, output, id);
            verifyRow(id, observed);
            JsonObject result = new JsonObject();
            result.addProperty("id", id);
            result.addProperty("status", "passed");
            result.add("observations", observed.observations());
            JsonObject origins = new JsonObject();
            for (String name : request.getAsJsonObject("required_classes").keySet()) {
                JsonObject origin = new JsonObject();
                origin.addProperty("pid", observed.pid);
                origins.add(name, origin);
            }
            result.add("origins", origins);
            JsonArray artifacts = new JsonArray();
            for (String name : List.of("stdout.log", "stderr.log", "class-load.log", "events.json")) addArtifact(artifacts, output, output.resolve(id).resolve(name));
            if (results.isEmpty()) {
                addArtifact(artifacts, output, output.resolve("actual-qualification.json"));
                for (String qualifier : List.of("QZero", "QOne", "QThree", "QMissingEnd")) {
                    for (String name : List.of("stdout.log", "stderr.log", "class-load.log", "events.json")) addArtifact(artifacts, output, output.resolve(qualifier).resolve(name));
                }
            }
            result.add("artifacts", artifacts);
            results.add(result);
            System.out.println("FreeType external trace completed " + id);
        }
        JsonObject receipt = new JsonObject();
        receipt.addProperty("schema", "strata-paired-jvm-preflight-receipt-v1");
        receipt.addProperty("status", "passed");
        receipt.addProperty("request_sha256", hash(requestPath));
        receipt.add("controls", results);
        writeNew(output.resolve("receipt.json"), receipt);
    }

    private static List<String> inventory() {
        List<String> ids = new ArrayList<>();
        for (int axis : List.of(1, 8, 16, 64, 256)) {
            for (String layout : List.of("TightPositive", "PaddedPositive", "TightNegative", "PaddedNegative")) ids.add("Converter" + axis + layout);
        }
        for (String fixture : List.of("SmallGlyphOne", "SmallGlyphTwo", "LargeGlyphOne", "LargeGlyphTwo")) {
            ids.add("Glyph-" + fixture);
            ids.add("Text-" + fixture);
        }
        ids.addAll(List.of("WarmFreeTypeRaster", "MissingFreeTypeGlyph", "EmptyFreeTypeGlyph", "AtlasRejectedGlyph", "ImageLimitRejectedGlyph", "StbGlyph", "BitmapGlyph", "SnapshotLoad", "EngineLifecycle", "SnapshotReplacement", "CompleteCleanTextFrame", "MalformedConverterInput"));
        return ids;
    }

    private static void verifyRow(String id, Trace trace) {
        requireCompleteBoundary(trace);
        require(trace.nativeReturns == trace.loads.size(), "Missing actual native wrapper returns");
        if (id.startsWith("Converter")) {
            require(trace.conversions.size() == 1 && trace.loads.isEmpty(), "Converter boundary included extra native work");
            var match = Pattern.compile("Converter(1|8|16|64|256)(Tight|Padded)(Positive|Negative)").matcher(id);
            require(match.matches(), "Unknown converter identity");
            int axis = Integer.parseInt(match.group(1));
            int pitch = (axis + (match.group(2).equals("Padded") ? 3 : 0)) * (match.group(3).equals("Negative") ? -1 : 1);
            JsonObject conversion = trace.conversions.get(0).getAsJsonObject();
            require(conversion.get("width").getAsInt() == axis && conversion.get("height").getAsInt() == axis && conversion.get("pitch").getAsInt() == pitch, "Actual converter arguments or source pitch changed");
        } else if (id.startsWith("Glyph-") || id.startsWith("Text-")) {
            require(trace.conversions.size() == 1 && integers(trace.loads).equals(RASTER), "Original measure/preflight/render sequence changed");
        } else if (id.equals("MissingFreeTypeGlyph")) {
            require(trace.loads.isEmpty() && trace.conversions.isEmpty(), "Missing glyph performed native loads or conversion");
        } else if (List.of("EmptyFreeTypeGlyph", "AtlasRejectedGlyph", "ImageLimitRejectedGlyph").contains(id)) {
            require(integers(trace.loads).equals(List.of(RASTER.get(0))) && trace.conversions.isEmpty(), "Empty or early rejected glyph performed prospective/render/conversion work");
        } else if (id.equals("MalformedConverterInput")) {
            require(trace.loads.isEmpty() && trace.conversions.size() == 3, "Original three malformed converter failures changed");
            for (JsonElement item : trace.conversions) require(item.getAsJsonObject().get("failed").getAsBoolean() && item.getAsJsonObject().get("read_count").getAsInt() == 0, "Rejected conversion read source pixels");
        } else if (List.of("WarmFreeTypeRaster", "StbGlyph", "BitmapGlyph", "SnapshotLoad", "CompleteCleanTextFrame").contains(id)) {
            require(trace.loads.isEmpty() && trace.conversions.isEmpty(), "No-conversion control entered FreeType raster work");
        } else {
            require(integers(trace.loads).equals(RASTER) && trace.conversions.size() == 1, "Lifecycle control changed its genuine glyph operation");
        }
        for (JsonElement item : trace.conversions) {
            JsonObject conversion = item.getAsJsonObject();
            if (conversion.get("failed").getAsBoolean() == false) require(conversion.get("read_count").getAsInt() == Math.multiplyExact(conversion.get("width").getAsInt(), conversion.get("height").getAsInt()), "Conversion did not read every source pixel exactly once");
        }
    }

    private static void requireCompleteBoundary(Trace trace) {
        require(trace.begins == 1 && trace.ends == 1 && trace.active == false, "Incomplete actual operation boundary");
    }

    private static void verifyQualificationCounts(Trace trace, int expected) {
        require(trace.qualificationReads.size() == expected && trace.loads.size() == expected, "Actual independent zero/one/three observer qualification failed");
    }

    private static void expectRejected(Runnable assertion) {
        boolean rejected = false;
        try { assertion.run(); } catch (IllegalStateException expected) { rejected = true; }
        require(rejected, "Actual malformed observation was accepted");
    }

    private static Trace observe(Path requestPath, JsonObject request, Path output, String id) throws Exception {
        Path directory = Files.createDirectory(output.resolve(id));
        List<String> paths = new ArrayList<>();
        for (JsonElement item : request.getAsJsonObject("classpath").getAsJsonArray("entries")) paths.add(item.getAsJsonObject().get("path").getAsString());
        String classpath = String.join(File.pathSeparator, paths);
        String font = System.getProperty("strata.performance.fontFixture");
        require(font != null, "Preserve the frozen font input");
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> options = connector.defaultArguments();
        options.get("home").setValue(System.getProperty("java.home"));
        options.get("main").setValue(WORKER + " " + quote(requestPath.toString()) + " " + id);
        Path classLog = directory.resolve("class-load.log");
        String relativeLog = Path.of(System.getProperty("user.dir")).toAbsolutePath().relativize(classLog).toString().replace(File.separatorChar, '/');
        options.get("options").setValue("-Xmx768m -XX:MaxMetaspaceSize=384m --enable-native-access=ALL-UNNAMED " + quote("-Xlog:class+load=info:file=" + relativeLog + ":pid,level,tags:filecount=0") + " -Dstrata.performance.fontFixture=" + quote(font) + " -classpath " + quote(classpath));
        options.get("suspend").setValue("true");
        VirtualMachine vm = connector.launch(options);
        Process process = vm.process();
        JsonObject origin = new JsonObject();
        origin.addProperty("task", "external-freetype-trace:" + id);
        origin.addProperty("main", WORKER);
        origin.addProperty("executable", Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
        origin.addProperty("classpath", classpath);
        System.out.println("paired.jvm.origin " + origin);
        CompletableFuture<Void> stdout = drain(process.getInputStream(), directory.resolve("stdout.log"));
        CompletableFuture<Void> stderr = drain(process.getErrorStream(), directory.resolve("stderr.log"));
        Trace trace = new Trace(process.pid(), id, vm);
        try {
            EventSet started = vm.eventQueue().remove(10000);
            require(started != null && started.stream().anyMatch(event -> event instanceof VMStartEvent) && vm.canGetMethodReturnValues(), "Missing actual JDI VM start or return capability");
            for (String name : TYPES) {
                ClassPrepareRequest prepared = vm.eventRequestManager().createClassPrepareRequest();
                prepared.addClassFilter(name);
                prepared.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                prepared.enable();
                for (ReferenceType type : vm.classesByName(name)) if (type.isPrepared()) trace.prepare(type);
            }
            for (String name : List.of(FACE, NATIVE, BITMAP)) {
                MethodExitRequest returned = vm.eventRequestManager().createMethodExitRequest();
                returned.addClassFilter(name);
                returned.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                trace.requests.add(returned);
            }
            ExceptionRequest failed = vm.eventRequestManager().createExceptionRequest(null, true, true);
            failed.addClassFilter(FACE);
            failed.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            trace.requests.add(failed);
            started.resume();
            boolean disconnected = false;
            while (disconnected == false) {
                EventSet events = vm.eventQueue().remove(1000);
                if (events == null) continue;
                for (Event event : events) {
                    if (event instanceof VMDisconnectEvent) disconnected = true;
                    else if (event instanceof ClassPrepareEvent prepared) trace.prepare(prepared.referenceType());
                    else if (event instanceof BreakpointEvent entered) trace.enter(entered);
                    else if (event instanceof MethodExitEvent returned) trace.exit(returned);
                    else if (event instanceof ExceptionEvent exception) trace.failure(exception);
                }
                if (disconnected == false) events.resume();
            }
            require(process.waitFor(30, TimeUnit.SECONDS), "Worker did not release naturally");
            stdout.get(10, TimeUnit.SECONDS);
            stderr.get(10, TimeUnit.SECONDS);
            require(process.exitValue() == 0, "Actual worker failed");
            require(Files.size(classLog) <= 64L * 1024 * 1024, "Class-load output overflow");
            for (String name : request.getAsJsonObject("required_classes").keySet()) {
                Pattern pattern = Pattern.compile("^\\[" + process.pid() + "\\]\\[info *\\]\\[class,load *\\] " + Pattern.quote(name) + " source: [^\\r\\n]+");
                List<String> loaded;
                try (var lines = Files.lines(classLog)) { loaded = lines.filter(line -> pattern.matcher(line).matches()).toList(); }
                require(loaded.size() == 1, "Missing or ambiguous actual worker class origin");
                System.out.println(loaded.get(0));
            }
            return trace;
        } finally {
            try { vm.dispose(); } catch (VMDisconnectedException disconnected) { /* Natural successful exit already disposed the connection. */ }
            writeNew(directory.resolve("events.json"), trace.raw());
        }
    }

    private static final class Trace {
        private final long pid;
        private final String id;
        private final VirtualMachine vm;
        private final Set<String> prepared = new HashSet<>();
        private final List<EventRequest> requests = new ArrayList<>();
        private final JsonArray definitions = new JsonArray();
        private final JsonArray loads = new JsonArray();
        private final JsonArray conversions = new JsonArray();
        private final JsonArray qualificationReads = new JsonArray();
        private boolean active;
        private long thread;
        private int begins;
        private int ends;
        private int nativeReturns;
        private JsonObject current;
        private long sourceBuffer;

        private Trace(long pid, String id, VirtualMachine vm) { this.pid = pid; this.id = id; this.vm = vm; }

        private void prepare(ReferenceType type) {
            String name = type.name();
            require(prepared.add(name), "Repeated actual observed type");
            List<Method> methods;
            if (name.equals(PRINT)) methods = type.methodsByName("println", "(Ljava/lang/String;)V");
            else if (name.equals(BUFFER)) methods = type.methodsByName("get", "(I)B");
            else if (name.equals(NATIVE)) methods = type.methodsByName("FT_Load_Glyph", LOAD);
            else if (name.equals(FACE)) methods = type.methodsByName("pixels");
            else methods = type.methodsByName("pitch", "()I");
            require(methods.size() == 1, "Missing or ambiguous actual observed overload");
            Method method = methods.get(0);
            Location location = method.locationOfCodeIndex(0);
            require(method.declaringType().equals(type) && method.isNative() == false && location != null && location.codeIndex() == 0, "Observed method lacks a concrete bytecode entry");
            BreakpointRequest entered = vm.eventRequestManager().createBreakpointRequest(location);
            entered.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            if (name.equals(PRINT)) entered.enable();
            else { requests.add(entered); if (active) entered.enable(); }
            JsonObject definition = new JsonObject();
            definition.addProperty("class", name);
            definition.addProperty("method", method.name());
            definition.addProperty("signature", method.signature());
            definition.addProperty("code_index", location.codeIndex());
            definitions.add(definition);
        }

        private void enter(BreakpointEvent event) throws Exception {
            Method method = event.location().method();
            String type = method.declaringType().name();
            if (type.equals(PRINT)) {
                var value = event.thread().frame(0).getArgumentValues().get(0);
                if (value instanceof StringReference marker) {
                    if (marker.value().equals("FreeType trace begin " + id)) {
                        require(active == false && begins == 0, "Duplicate operation begin");
                        active = true; begins++; thread = event.thread().uniqueID();
                        for (EventRequest request : requests) request.enable();
                    } else if (marker.value().equals("FreeType trace end " + id)) {
                        require(active && event.thread().uniqueID() == thread && current == null, "Wrong-thread or unfinished conversion boundary");
                        active = false; ends++;
                        for (EventRequest request : requests) request.disable();
                    }
                }
                return;
            }
            if (active == false || event.thread().uniqueID() != thread) return;
            var frame = event.thread().frame(0);
            var args = frame.getArgumentValues();
            if (type.equals(NATIVE)) loads.add(((IntegerValue) args.get(2)).value());
            else if (type.equals(FACE)) {
                require(current == null, "Overlapping actual conversions");
                current = new JsonObject();
                current.addProperty("width", ((IntegerValue) args.get(1)).value());
                current.addProperty("height", ((IntegerValue) args.get(2)).value());
                current.addProperty("failed", false);
                current.add("read_indices", new JsonArray());
                sourceBuffer = 0;
            } else if (type.equals(BUFFER)) {
                int index = ((IntegerValue) args.get(0)).value();
                if (id.startsWith("Q")) qualificationReads.add(index);
                else if (current != null) {
                    long buffer = frame.thisObject().uniqueID();
                    if (sourceBuffer == 0) sourceBuffer = buffer;
                    require(sourceBuffer == buffer && current.has("pitch"), "Conversion changed source buffers or did not observe its native pitch");
                    int width = current.get("width").getAsInt();
                    int height = current.get("height").getAsInt();
                    int pitch = current.get("pitch").getAsInt();
                    JsonArray indices = current.getAsJsonArray("read_indices");
                    require(0 < width && 0 < height && indices.size() < Math.multiplyExact(width, height), "Unexpected extra or nonpositive pixel read");
                    int row = indices.size() / width;
                    int physical = 0 <= pitch ? row : height - row - 1;
                    int expected = Math.toIntExact(physical * Math.abs((long) pitch) + indices.size() % width);
                    require(index == expected, "Skipped, repeated, reordered or padding source read");
                    indices.add(index);
                }
            }
        }

        private void exit(MethodExitEvent event) {
            if (active == false || event.thread().uniqueID() != thread) return;
            Method method = event.method();
            String type = method.declaringType().name();
            if (type.equals(NATIVE) && method.name().equals("FT_Load_Glyph") && method.signature().equals(LOAD)) {
                require(((IntegerValue) event.returnValue()).value() == 0, "Actual native call failed");
                nativeReturns++;
            } else if (type.equals(BITMAP) && method.name().equals("pitch") && method.signature().equals("()I") && current != null) {
                require(current.has("pitch") == false, "Repeated converter pitch load");
                current.addProperty("pitch", ((IntegerValue) event.returnValue()).value());
            } else if (type.equals(FACE) && method.name().equals("pixels")) finish(false);
        }

        private void failure(ExceptionEvent event) {
            if (active && event.thread().uniqueID() == thread && current != null && event.location().method().name().equals("pixels")) finish(true);
        }

        private void finish(boolean failed) {
            require(current != null, "Missing observed conversion entry");
            current.addProperty("failed", failed);
            current.addProperty("read_count", current.getAsJsonArray("read_indices").size());
            conversions.add(current);
            current = null;
        }

        private JsonObject observations() {
            JsonObject result = new JsonObject();
            result.addProperty("begins", begins);
            result.addProperty("ends", ends);
            result.addProperty("active_at_exit", active);
            result.addProperty("native_returns", nativeReturns);
            result.add("native_load_flags", loads.deepCopy());
            result.add("conversions", conversions.deepCopy());
            result.add("qualification_read_indices", qualificationReads.deepCopy());
            result.add("method_entry_breakpoints", definitions.deepCopy());
            return result;
        }

        private JsonObject raw() {
            JsonObject result = new JsonObject();
            result.addProperty("pid", pid);
            result.addProperty("id", id);
            result.add("observations", observations());
            if (current != null) result.add("unfinished_conversion", current.deepCopy());
            return result;
        }
    }

    private static CompletableFuture<Void> drain(InputStream input, Path path) {
        return CompletableFuture.runAsync(() -> {
            try (input; var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    require(total <= 2L * 1024 * 1024, "Worker console overflow");
                    output.write(buffer, 0, count);
                }
            } catch (Exception failure) { throw new IllegalStateException("Could not retain actual worker console", failure); }
        });
    }

    private static List<Integer> integers(JsonArray array) {
        List<Integer> result = new ArrayList<>();
        for (JsonElement value : array) result.add(value.getAsInt());
        return result;
    }

    private static String quote(String value) { require(value.contains("\"") == false && value.contains("\n") == false && value.contains("\r") == false, "Unsafe JVM argument"); return "\"" + value + "\""; }
    private static void require(boolean condition, String message) { if (condition == false) throw new IllegalStateException(message); }
    private static void writeNew(Path path, JsonObject value) throws Exception { Files.writeString(path, value + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); }
    private static String hash(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    private static String classHash(Class<?> type) throws Exception {
        try (InputStream input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            require(input != null, "Missing actual compiled trace class");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }
    private static void addArtifact(JsonArray artifacts, Path output, Path path) throws Exception {
        JsonObject item = new JsonObject();
        item.addProperty("path", output.relativize(path).toString().replace(File.separatorChar, '/'));
        item.addProperty("sha256", hash(path));
        artifacts.add(item);
    }
}
