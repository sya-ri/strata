package dev.s7a.strata.quality.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.s7a.strata.performance.ArtifactIdentity;
import dev.s7a.strata.performance.JvmPerformanceInputs;
import dev.s7a.strata.performance.JvmPerformanceMeter;
import dev.s7a.strata.performance.LoadedArtifactMetadata;
import dev.s7a.strata.performance.PerformanceJson;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Delegates the unchanged complete indexed-font consumer on the existing untimed compiled route. */
public final class IndexedFontCpuPreflight {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    /** Original independently required group order; completion is read from real original stdout. */
    private enum Control {
        Empty, ValidHashes, ValidPositions, InvalidAscii, Uppercase, Lengths, Whitespace,
        NonAscii, Surrogates, Escapes, HashShapes, DocumentShapes, InvalidPositions,
        FailFastOrder, Aliases, PathHashPrecedence, Paths, PathCeilings, ByteCeilings,
        EntryCeilings, JsonCeilings, Constructors, ObjectResolution, SymlinksAndIo,
        StreamClosure, FreshBytes, CapturedIndex, IndependentOwners, SnapshotPriority,
        SnapshotRetirement, CommonQualification, ZeroWorkAndPortableText
    }

    private IndexedFontCpuPreflight() {}

    /** Receives one pinned generic request; a failed original control never publishes a success receipt. */
    public static void main(String[] arguments) throws Exception {
        require(arguments.length == 1, "Supply one generic preflight request");
        Path requestPath = Path.of(arguments[0]).toAbsolutePath().normalize();
        String requestHash = ArtifactIdentity.INSTANCE.file(requestPath);
        JsonObject request = document(requestPath);
        require(request.get("schema").getAsString().equals("strata-paired-jvm-preflight-request-v1"), "Wrong generic request");
        require(request.get("main").getAsString().equals(IndexedFontCpuPreflight.class.getName()), "Wrong compiled main");
        JsonArray controls = request.getAsJsonArray("controls");
        require(controls.size() == 1 && controls.get(0).getAsJsonObject().getAsJsonArray("arguments").size() == 0, "Require one complete original consumer");
        String controlId = controls.get(0).getAsJsonObject().get("id").getAsString();
        Path output = Path.of(request.get("output").getAsString()).toAbsolutePath().normalize();
        require(Files.isDirectory(output) && requestPath.getParent().equals(output), "Wrong owned output");
        JsonObject origins = origins(request);
        Path libraryInputs = output.resolve("original-control-inputs.properties");
        prepareLibraryInputs(request, libraryInputs);
        String previousInputs = System.getProperty("strata.performance.inputs");
        String previousFont = System.getProperty("strata.performance.fontFixture");
        try {
            System.setProperty("strata.performance.inputs", libraryInputs.toString());
            System.setProperty("strata.performance.fontFixture", request.getAsJsonObject("inputs").getAsJsonObject("cc0-geometric-font").get("path").getAsString());
            collect(requestPath, requestHash, request, output, origins, controlId);
        } finally {
            restore("strata.performance.inputs", previousInputs);
            restore("strata.performance.fontFixture", previousFont);
        }
    }

    /** Resolves preserved control JARs from the generic request, without inventing Maven coordinates. */
    private static void prepareLibraryInputs(JsonObject request, Path destination) throws Exception {
        Set<Path> runtimes = new HashSet<>();
        request.getAsJsonObject("runtime").entrySet().forEach(entry -> runtimes.add(Path.of(entry.getValue().getAsJsonObject().get("path").getAsString()).toAbsolutePath().normalize()));
        Properties libraries = new Properties();
        JsonArray mappings = new JsonArray();
        Path fixture = Path.of(IndexedFontCpuEvidence.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        Path collector = Path.of(JvmPerformanceMeter.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        JsonArray entries = request.getAsJsonObject("classpath").getAsJsonArray("entries");
        for (int index = 0; index < entries.size(); index++) {
            Path path = Path.of(entries.get(index).getAsJsonObject().get("path").getAsString()).toAbsolutePath().normalize();
            JsonObject pin = entries.get(index).getAsJsonObject();
            JsonObject mapping = pin.deepCopy();
            mapping.addProperty("ordinal", index);
            String category = runtimes.contains(path) ? "runtime" : path.equals(fixture) ? "fixture" : path.equals(collector) ? "collector" : "other-preserved-classpath";
            mapping.addProperty("category", category);
            if (Files.isRegularFile(path) && !runtimes.contains(path)) {
                String label = "prepared-classpath-" + index;
                libraries.setProperty(label, path.toString());
                mapping.addProperty("input_label", label);
            }
            mappings.add(mapping);
        }
        require(!libraries.isEmpty(), "Missing actual control JARs");
        try (var writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            libraries.store(writer, "Actual preserved nonruntime classpath JARs; labels are classpath indices");
        }
        JsonObject identity = new JsonObject();
        identity.add("entries", mappings);
        write(destination.resolveSibling("original-classpath-inputs.json"), identity);
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }

    /** Owns the original reporter and publishes only after complete real evidence has been verified. */
    private static void collect(Path requestPath, String requestHash, JsonObject request, Path output, JsonObject origins, String controlId) throws Exception {
        JsonObject consumed = inputs(request);
        Map<String, String> manifests = new LinkedHashMap<>();
        for (String name : List.of("inputs", "fixtureInputs")) manifests.put(name, ArtifactIdentity.INSTANCE.file(Path.of(Objects.requireNonNull(System.getProperty("strata.performance." + name)))));
        verifyClosure(request);
        JsonObject invocation = new JsonObject();
        Charset stdoutCharset = (Charset) PrintStream.class.getMethod("charset").invoke(System.out);
        Charset stderrCharset = (Charset) PrintStream.class.getMethod("charset").invoke(System.err);
        invocation.addProperty("stdout_charset", stdoutCharset.name());
        invocation.addProperty("stderr_charset", stderrCharset.name());
        invocation.addProperty("request_sha256", requestHash);
        invocation.addProperty("pid", ProcessHandle.current().pid());
        invocation.addProperty("process_start", ProcessHandle.current().info().startInstant().orElseThrow().toString());
        invocation.add("actual_vm_arguments", JSON.toJsonTree(ManagementFactory.getRuntimeMXBean().getInputArguments()));
        invocation.addProperty("actual_classpath", System.getProperty("java.class.path"));
        invocation.add("origins", origins.deepCopy());
        invocation.add("inputs", consumed.deepCopy());
        invocation.add("input_manifest_hashes", JSON.toJsonTree(manifests));
        invocation.add("source_bindings", request.get("sources").deepCopy());
        write(output.resolve("invocation.json"), invocation);
        Path reportPath = output.resolve("original-report.json");
        Path stdoutPath = output.resolve("original-stdout.log");
        Path stderrPath = output.resolve("original-stderr.log");
        // The original reporter alone owns controls, all sampling, states and teardown.
        // Streams retain its unchanged bytes and still forward them to the owning phase console.
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        try (PrintStream retainedOut = tee(stdoutPath, stdout, stdoutCharset); PrintStream retainedErr = tee(stderrPath, stderr, stderrCharset)) {
            System.setOut(retainedOut);
            System.setErr(retainedErr);
            try {
                IndexedFontCpuEvidence.main(new String[] {reportPath.toString(), "0"});
                require(!retainedOut.checkError() && !retainedErr.checkError(), "Original console retention failed");
            } finally {
                System.setOut(stdout);
                System.setErr(stderr);
            }
        }
        JsonObject report = document(reportPath);
        verifyReport(report, consumed, request);
        List<String> actualControls = Files.readAllLines(stdoutPath, stdoutCharset).stream()
            .filter(line -> line.startsWith("indexed-font-control=")).toList();
        List<String> expectedControls = Arrays.stream(Control.values())
            .map(control -> "indexed-font-control=" + (control.ordinal() + 1) + ":" + control.name() + ":passed").toList();
        require(actualControls.equals(expectedControls), "Missing, duplicate or reordered original control groups");
        require(Files.readAllLines(stdoutPath, stdoutCharset).stream()
            .filter(line -> line.startsWith("indexed-font-controls=32;")).count() == 1, "Missing original whole-control terminal");
        require(requestHash.equals(ArtifactIdentity.INSTANCE.file(requestPath)), "Changed request");
        require(consumed.equals(inputs(request)) && origins.equals(origins(request)), "Changed consumed or loaded origins");
        for (Map.Entry<String, String> manifest : manifests.entrySet()) require(manifest.getValue().equals(ArtifactIdentity.INSTANCE.file(Path.of(Objects.requireNonNull(System.getProperty("strata.performance." + manifest.getKey()))))), "Changed original input manifest bytes");
        verifyClosure(request);
        JsonObject observation = new JsonObject();
        observation.addProperty("pid", ProcessHandle.current().pid());
        observation.addProperty("original_run_id", report.get("run_id").getAsString());
        observation.add("actual_control_lines", JSON.toJsonTree(actualControls));
        observation.add("operation_names", JSON.toJsonTree(phaseNames()));
        observation.add("lifecycle_names", JSON.toJsonTree(lifetimeNames()));
        observation.add("original_plan", JSON.toJsonTree(Map.of("warmup", 30, "samples", 60)));
        observation.add("runtime_metadata", report.get("runtime_metadata").deepCopy());
        observation.add("fixture_hashes", report.get("fixture_hashes").deepCopy());
        observation.add("source_bindings", request.get("sources").deepCopy());
        observation.add("inputs", consumed.deepCopy());
        write(output.resolve("observations.json"), observation);
        JsonArray artifacts = new JsonArray();
        for (String name : List.of("invocation.json", "original-control-inputs.properties", "original-classpath-inputs.json", "original-report.json", "original-stdout.log", "original-stderr.log", "observations.json")) {
            JsonObject artifact = new JsonObject();
            artifact.addProperty("path", name);
            artifact.addProperty("sha256", ArtifactIdentity.INSTANCE.file(output.resolve(name)));
            artifacts.add(artifact);
        }
        JsonObject result = new JsonObject();
        result.addProperty("id", controlId);
        result.addProperty("status", "passed");
        result.add("observations", observation);
        result.add("origins", origins);
        result.add("artifacts", artifacts);
        JsonArray results = new JsonArray();
        results.add(result);
        JsonObject receipt = new JsonObject();
        receipt.addProperty("schema", "strata-paired-jvm-preflight-receipt-v1");
        receipt.addProperty("status", "passed");
        receipt.addProperty("request_sha256", requestHash);
        receipt.add("controls", results);
        write(output.resolve("receipt.json"), receipt);
    }

    /** Checks every loaded required code source against its prepared full-byte identity. */
    private static JsonObject origins(JsonObject request) throws Exception {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : request.getAsJsonObject("required_classes").entrySet()) {
            Class<?> type = Class.forName(entry.getKey(), false, ClassLoader.getSystemClassLoader());
            JsonObject pin = entry.getValue().getAsJsonObject();
            Path expected = Path.of(pin.get("path").getAsString()).toAbsolutePath().normalize();
            Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            require(actual.equals(expected) && ArtifactIdentity.INSTANCE.fullCodeSource(type).equals(pin.get("sha256").getAsString()), "Wrong loaded required class: " + entry.getKey());
            JsonObject origin = new JsonObject();
            origin.addProperty("pid", ProcessHandle.current().pid());
            result.add(entry.getKey(), origin);
        }
        return result;
    }

    /** Pins the actual reporter mappings, including external libraries and the real native font input. */
    private static JsonObject inputs(JsonObject request) {
        Map<String, Path> files = new LinkedHashMap<>();
        for (String name : List.of("inputs", "fixtureInputs")) {
            JvmPerformanceInputs.INSTANCE.read(Path.of(Objects.requireNonNull(System.getProperty("strata.performance." + name))))
                .forEach((label, path) -> {
                    Path previous = files.putIfAbsent(label, path);
                    require(previous == null || previous.equals(path), "Ambiguous consumed input: " + label);
                });
        }
        Path font = Path.of(Objects.requireNonNull(System.getProperty("strata.performance.fontFixture")));
        Path previous = files.putIfAbsent("cc0-geometric-font", font);
        require(previous == null || previous.toAbsolutePath().normalize().equals(font.toAbsolutePath().normalize()), "Wrong native font mapping");
        JsonObject frozen = request.getAsJsonObject("inputs");
        JsonObject result = new JsonObject();
        files.forEach((label, path) -> result.addProperty(label, ArtifactIdentity.INSTANCE.file(path)));
        for (Map.Entry<String, JsonElement> entry : frozen.entrySet()) {
            JsonObject pin = entry.getValue().getAsJsonObject();
            require(files.containsKey(entry.getKey()), "Unconsumed frozen input: " + entry.getKey());
            require(files.get(entry.getKey()).toAbsolutePath().normalize().equals(Path.of(pin.get("path").getAsString()).toAbsolutePath().normalize()), "Wrong consumed input path");
            require(result.get(entry.getKey()).getAsString().equals(pin.get("sha256").getAsString()), "Changed frozen input");
        }
        Set<String> labels = new HashSet<>(List.of("indexed-counts", "indexed-sha256"));
        for (IndexedFontInput input : IndexedFontInput.values()) {
            String prefix = "indexed-" + input.name() + "-";
            for (String suffix : List.of("index", "object", "archive", "custom", "extra")) labels.add(prefix + suffix);
            Path root = files.get(prefix + "index").toAbsolutePath().normalize().getParent();
            require(files.get(prefix + "object").toAbsolutePath().normalize().getParent().getParent().equals(root.resolve("objects")), "Wrong original object-store relationship");
            require(files.get(prefix + "archive").toAbsolutePath().normalize().equals(root.resolve("client.jar")), "Wrong original archive relationship");
            require(files.get(prefix + "custom").toAbsolutePath().normalize().equals(root.resolve("pack/assets/minecraft/font/default.json")), "Wrong original custom-pack relationship");
            require(files.get(prefix + "extra").toAbsolutePath().normalize().equals(root.resolve("pack/assets/test/font/directory.json")), "Wrong original extra-pack relationship");
        }
        require(frozen.keySet().stream().filter(name -> name.startsWith("indexed-")).collect(Collectors.toSet()).equals(labels), "Missing original 47 input labels");
        require(frozen.has("cc0-geometric-font"), "Missing declared real native font");
        return result;
    }

    /** Rejects changed original/preserved classpaths, qualification or runtime archives. */
    private static void verifyClosure(JsonObject request) {
        JsonArray entries = request.getAsJsonObject("classpath").getAsJsonArray("entries");
        List<Path> expected = new ArrayList<>();
        for (JsonElement value : entries) {
            JsonObject entry = value.getAsJsonObject();
            expected.add(Path.of(entry.get("path").getAsString()).toAbsolutePath().normalize());
            verifyPin(entry, "path");
            verifyPin(entry, "source");
        }
        List<Path> actual = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
            .map(value -> Path.of(value).toAbsolutePath().normalize()).toList();
        require(actual.equals(expected), "Changed actual prepared classpath");
        verifyPin(request.getAsJsonObject("qualification"), "path");
        request.getAsJsonObject("runtime").entrySet().forEach(entry -> verifyPin(entry.getValue().getAsJsonObject(), "path"));
        require(request.getAsJsonObject("sources").keySet().equals(Set.of("fixture", "baseline", "candidate")), "Incomplete source binding");
    }

    private static void verifyPin(JsonObject pin, String field) {
        Path path = Path.of(pin.get(field).getAsString());
        String hash = Files.isDirectory(path) ? ArtifactIdentity.INSTANCE.tree(path) : ArtifactIdentity.INSTANCE.file(path);
        require(hash.equals(pin.get("sha256").getAsString()), "Changed pinned bytes: " + field);
    }

    /** Requires the unchanged reporter's exact complete plan, distributions and lifecycle boundaries. */
    private static void verifyReport(JsonObject report, JsonObject inputs, JsonObject request) throws Exception {
        require(report.get("contract").getAsString().equals("indexed-font-supplemental-cpu-v1") && report.get("status").getAsString().equals("passed"), "Wrong original report");
        require(report.get("repetition").getAsInt() == 0 && report.get("warmup").getAsInt() == 30 && report.get("samples").getAsInt() == 60 && report.get("controls").getAsInt() == 32, "Changed original plan");
        require(report.getAsJsonObject("inputs").equals(inputs), "Wrong original report input hashes");
        require(report.getAsJsonObject("collector_identity").equals(PerformanceJson.INSTANCE.collectorIdentity()), "Wrong loaded collector");
        LoadedArtifactMetadata.INSTANCE.verifyComplete(report.getAsJsonObject("runtime_metadata"));
        Map<String, String> representatives = new LinkedHashMap<>();
        representatives.put("api", "dev.s7a.strata.component.UiScope");
        representatives.put("core", "dev.s7a.strata.runtime.spi.RuntimeUiSession");
        representatives.put("headless", "dev.s7a.strata.runtime.headless.HeadlessImage");
        representatives.put("minecraft", "dev.s7a.strata.runtime.minecraft.MinecraftUiHost");
        representatives.put("fonts", "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory");
        require(report.getAsJsonObject("runtime_metadata").equals(LoadedArtifactMetadata.INSTANCE.capture(ClassLoader.getSystemClassLoader(), representatives, representatives.keySet())), "Changed original loaded runtime metadata");
        List<Class<?>> fixtures = new ArrayList<>();
        for (String name : List.of("IndexedFontAssetBenchmark", "IndexedFontReuseBenchmark", "IndexedFontInput", "IndexedFontAssetFiles", "IndexedFontAssetControls", "IndexedFontCpuEvidence", "PortableTextBenchmark", "PortableTextWorkEvidence", "ComponentFontAssets", "ComponentProfile", "FontPerformanceAssets")) fixtures.add(Class.forName(IndexedFontCpuEvidence.class.getPackageName() + "." + name, false, ClassLoader.getSystemClassLoader()));
        fixtures.add(Class.forName("dev.s7a.strata.integration.docs.FontResourceExampleKt", false, ClassLoader.getSystemClassLoader()));
        require(report.getAsJsonObject("fixture_hashes").equals(LoadedArtifactMetadata.INSTANCE.captureClassHashes(fixtures)), "Changed original compiled fixture closure");
        require(request.getAsJsonObject("runtime").keySet().equals(Set.of(":api", ":runtime:core", ":runtime:headless", ":runtime:minecraft", ":runtime:minecraft-fonts-lwjgl")), "Wrong original runtime inventory");
        IndexedFontCpuReportValidation.verifyPhases(report.getAsJsonArray("phases"), phaseNames(), 60);
        IndexedFontCpuReportValidation.verifyPhases(report.getAsJsonArray("setup_teardown"), lifetimeNames(), 1);
    }

    private static List<String> phaseNames() {
        List<String> names = new ArrayList<>();
        for (IndexedFontInput input : IndexedFontInput.values()) {
            for (String operation : List.of("constructPaths", "constructRead", "loadExample")) names.add(operation + ":" + input.name());
        }
        for (IndexedFontInput input : List.of(IndexedFontInput.Distinct1, IndexedFontInput.Shared1, IndexedFontInput.Distinct4096, IndexedFontInput.Shared4096)) {
            for (String operation : List.of("reusedPaths", "reusedRead")) names.add(operation + ":" + input.name());
        }
        for (PortableTextBenchmark.Face face : PortableTextBenchmark.Face.values()) {
            for (int density : List.of(1, 4)) {
                for (String operation : List.of("extractGlyphs", "composePreparedGlyphs")) names.add(operation + ":" + face.name() + ":" + density);
            }
        }
        return names;
    }

    private static List<String> lifetimeNames() {
        List<String> names = new ArrayList<>();
        for (IndexedFontInput input : IndexedFontInput.values()) {
            for (String operation : List.of("setup", "teardown")) names.add("cold:" + input.name() + ":" + operation);
        }
        for (IndexedFontInput input : List.of(IndexedFontInput.Distinct1, IndexedFontInput.Shared1, IndexedFontInput.Distinct4096, IndexedFontInput.Shared4096)) {
            for (String operation : List.of("setup", "teardown")) names.add("reuse:" + input.name() + ":" + operation);
        }
        for (PortableTextBenchmark.Face face : PortableTextBenchmark.Face.values()) {
            for (int density : List.of(1, 4)) names.add("portable:" + face.name() + ":" + density + ":setup");
        }
        return names;
    }

    private static PrintStream tee(Path path, PrintStream forwarded, Charset charset) throws Exception {
        OutputStream retained = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new PrintStream(new OutputStream() {
            private long bytes;
            @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }
            @Override public void write(byte[] value, int offset, int length) throws IOException {
                bytes = Math.addExact(bytes, length);
                if (16L * 1024 * 1024 < bytes) throw new IOException("Original console exceeds the unchanged phase bound");
                retained.write(value, offset, length);
                forwarded.write(value, offset, length);
            }
            @Override public void flush() throws IOException { retained.flush(); forwarded.flush(); }
            @Override public void close() throws IOException { retained.close(); }
        }, true, charset);
    }

    private static JsonObject document(Path path) throws Exception {
        require(Files.isRegularFile(path) && Files.size(path) <= 64L * 1024 * 1024, "Missing or oversized evidence");
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void write(Path path, JsonObject value) throws Exception {
        Files.writeString(path, JSON.toJson(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
