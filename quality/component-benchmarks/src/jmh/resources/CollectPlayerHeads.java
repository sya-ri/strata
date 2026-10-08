import dev.s7a.strata.performance.JmhFixtureSelection;
import dev.s7a.strata.performance.JmhPerformanceRunner;
import dev.s7a.strata.performance.JmhWorkloadInventory;
import dev.s7a.strata.performance.JvmPerformanceInputs;
import dev.s7a.strata.quality.benchmark.PlayerHeadLayerBenchmark;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Frozen source-launch entry point for the fixed PlayerHead corpus and existing shared JMH collector.
 * Current-source binary registration remains a separate mandatory CI gate.
 * Exact measured baseline/candidate archives are certified by the shared collector and per-iteration fork verifier.
 */
public final class CollectPlayerHeads {
    private CollectPlayerHeads() {
    }

    /** Accepts fresh output, repetition and unchanged standard JMH options; it creates no timing loop. */
    public static void main(String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("Output, repetition and JMH options are required.");
        List<Class<?>> fixtures = List.of(PlayerHeadLayerBenchmark.class);
        List<String> includes = JmhFixtureSelection.INSTANCE.includes(fixtures);
        String mode = System.getProperty("strata.performance.mode", "avgt");
        var expected = JmhWorkloadInventory.INSTANCE.capture(fixtures, Set.of(mode), Map.of(), includes);
        if (expected.size() != 220) throw new IllegalStateException("The fixed complete PlayerHead matrix changed.");
        JmhFixtureSelection.INSTANCE.verifyIncludes(expected, includes);
        PlayerHeadLayerBenchmark.verifyWork();
        var arguments = new ArrayList<String>(includes);
        arguments.addAll(List.of(args).subList(2, args.length));
        var targets = new LinkedHashMap<String, String>();
        targets.put("api", "dev.s7a.strata.component.UiScope");
        targets.put("core", "dev.s7a.strata.runtime.spi.RuntimeUiSession");
        targets.put("headless", "dev.s7a.strata.runtime.headless.HeadlessImage");
        targets.put("minecraft", "dev.s7a.strata.runtime.minecraft.MinecraftUiHost");
        targets.put("fonts", "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory");
        var inputs = new LinkedHashMap<>(JvmPerformanceInputs.INSTANCE.read(Path.of(System.getProperty("strata.performance.inputs"))));
        inputs.put("fixed-source-entry", Path.of(System.getProperty("strata.performance.sourceEntry")));
        inputs.put("fixed-source-pins", Path.of(System.getProperty("strata.performance.sourcePins")));
        JmhPerformanceRunner.INSTANCE.run(arguments.toArray(String[]::new), fixtures, targets,
            Path.of(args[0]), Integer.parseInt(args[1]), expected, inputs);
    }
}
