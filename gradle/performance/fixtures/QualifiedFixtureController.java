package independent.fixture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Independent compiled test controller; never installed in the measured runtime archives. */
public final class QualifiedFixtureController {
    /** Decoded negative controls exercise the actual process and receipt boundaries. */
    private enum Behavior { Complete, Missing, Altered, Failure }

    private QualifiedFixtureController() {}

    /** Runs the complete declared inventory and retains actual observations before returning or failing. */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Supply the pinned request");
        Path requestPath = Path.of(arguments[0]);
        JsonObject request = JsonParser.parseString(Files.readString(requestPath)).getAsJsonObject();
        Path output = Path.of(request.get("output").getAsString());
        JsonObject origins = new JsonObject();
        for (String name : request.getAsJsonObject("required_classes").keySet()) {
            Class.forName(name, false, ClassLoader.getSystemClassLoader());
            JsonObject origin = new JsonObject();
            origin.addProperty("pid", ProcessHandle.current().pid());
            origins.add(name, origin);
        }
        JsonArray results = new JsonArray();
        int completed = 0;
        for (JsonElement entry : request.getAsJsonArray("controls")) {
            JsonObject control = entry.getAsJsonObject();
            String id = control.get("id").getAsString();
            JsonArray options = control.getAsJsonArray("arguments");
            Behavior behavior = Behavior.valueOf(options.get(0).getAsString());
            int length = options.get(1).getAsInt();
            if (length < 1 || 4096 < length) throw new IllegalArgumentException("Unbounded control fixture");
            long sum = 0;
            for (int index = 0; index < length; index++) sum += index;
            long oracle = (long) length * (length - 1) / 2;
            if (sum != oracle) throw new IllegalStateException("Independent full-inventory parity failed");
            Path artifact = output.resolve(id + ".json");
            JsonObject observation = new JsonObject();
            observation.addProperty("actual_visits", length);
            observation.addProperty("actual_sum", sum);
            observation.addProperty("independent_sum", oracle);
            Files.writeString(artifact, observation.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            if (behavior == Behavior.Failure) throw new IllegalStateException("Required independent controller failure");
            if (behavior == Behavior.Missing) continue;
            if (behavior == Behavior.Altered) {
                Path changed = Path.of(request.getAsJsonObject("required_classes").getAsJsonObject("org.openjdk.jmh.runner.Runner").get("path").getAsString());
                Files.write(changed, new byte[] { 1 }, StandardOpenOption.APPEND);
            }
            JsonObject result = new JsonObject();
            result.addProperty("id", id);
            result.addProperty("status", "passed");
            result.add("observations", observation);
            result.add("origins", origins.deepCopy());
            JsonObject retained = new JsonObject();
            retained.addProperty("path", artifact.getFileName().toString());
            retained.addProperty("sha256", hash(artifact));
            JsonArray artifacts = new JsonArray();
            artifacts.add(retained);
            result.add("artifacts", artifacts);
            results.add(result);
            completed++;
        }
        JsonObject receipt = new JsonObject();
        receipt.addProperty("schema", "strata-paired-jvm-preflight-receipt-v1");
        receipt.addProperty("status", "passed");
        receipt.addProperty("request_sha256", hash(requestPath));
        receipt.add("controls", results);
        Files.writeString(output.resolve("receipt.json"), receipt.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("Independent compiled controls completed: " + completed);
        // Keep the real application VM observable by the existing owning Job generation sampler.
        Thread.sleep(250);
    }

    /** Hashes actual retained bytes independently from the Python controller. */
    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
