package dev.s7a.strata.quality.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.s7a.strata.performance.PerformanceJson;
import java.util.ArrayList;
import java.util.List;

/** Verifies complete original meter distributions without introducing another measurement engine. */
final class IndexedFontCpuReportValidation {
    private IndexedFontCpuReportValidation() {}

    /** Rejects omitted, reordered, repeated or changed raw reports; unsupported counters remain null. */
    static void verifyPhases(JsonArray phases, List<String> names, int samples) {
        require(phases.size() == names.size(), "Incomplete original boundary matrix");
        for (int index = 0; index < names.size(); index++) {
            JsonObject phase = phases.get(index).getAsJsonObject();
            require(phase.get("name").getAsString().equals(names.get(index)) && phase.get("samples").getAsInt() == samples, "Wrong original boundary");
            for (String field : List.of("wall_distribution", "owner_thread_cpu_distribution", "owner_thread_allocation_distribution")) {
                JsonElement value = phase.get(field);
                require(value != null && (!field.equals("wall_distribution") || !value.isJsonNull()), "Missing original distribution");
                if (value.isJsonNull()) continue;
                JsonObject distribution = value.getAsJsonObject();
                JsonArray raw = distribution.getAsJsonArray("raw");
                require(distribution.get("samples").getAsInt() == samples && raw.size() == samples, "Partial original distribution");
                List<Long> values = new ArrayList<>();
                raw.forEach(sample -> values.add(sample.getAsLong()));
                require(values.stream().allMatch(sample -> 0 <= sample) && distribution.equals(PerformanceJson.INSTANCE.distribution(values)), "Changed original distribution");
            }
            for (String counter : List.of("owner_thread_cpu_ns", "owner_thread_allocated_bytes", "process_cpu_ns", "all_threads_allocated_bytes", "gc_collections_during_samples", "gc_collection_time_ms_during_samples")) {
                require(phase.has(counter), "Missing nullable original counter");
            }
            for (String[] binding : List.of(new String[] {"wall_distribution", "wall_total_ns"}, new String[] {"owner_thread_cpu_distribution", "owner_thread_cpu_ns"}, new String[] {"owner_thread_allocation_distribution", "owner_thread_allocated_bytes"})) {
                JsonElement distribution = phase.get(binding[0]);
                JsonElement total = phase.get(binding[1]);
                require(total != null && (distribution.isJsonNull() ? total.isJsonNull() : !total.isJsonNull() && total.getAsLong() == distribution.getAsJsonObject().get("total_ns").getAsLong()), "Original counter/distribution total differs");
            }
            JsonObject wall = phase.getAsJsonObject("wall_distribution");
            for (String key : List.of("p50_ns", "p95_ns", "p99_ns", "max_ns")) require(phase.get("wall_" + key).equals(wall.get(key)), "Original wall summary differs");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
