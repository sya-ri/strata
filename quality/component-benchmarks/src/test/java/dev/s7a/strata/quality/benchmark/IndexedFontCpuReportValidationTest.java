package dev.s7a.strata.quality.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.s7a.strata.performance.JvmPerformanceMeter;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises admission against real shared-meter output and independently corrupted copies. */
class IndexedFontCpuReportValidationTest {
    @Test
    void acceptsCompleteActualSharedMeterReport() {
        assertDoesNotThrow(() -> IndexedFontCpuReportValidation.verifyPhases(actual(), List.of("known"), 3));
    }

    @Test
    void rejectsMissingDuplicateAndReorderedBoundaries() {
        JsonArray report = actual();
        assertThrows(IllegalStateException.class, () -> IndexedFontCpuReportValidation.verifyPhases(report, List.of("known", "missing"), 3));
        report.add(report.get(0).deepCopy());
        assertThrows(IllegalStateException.class, () -> IndexedFontCpuReportValidation.verifyPhases(report, List.of("known", "other"), 3));
        report.get(0).getAsJsonObject().addProperty("name", "other");
        assertThrows(IllegalStateException.class, () -> IndexedFontCpuReportValidation.verifyPhases(report, List.of("known", "other"), 3));
    }

    @Test
    void rejectsPartialAndChangedRawDistributions() {
        JsonArray report = actual();
        JsonObject phase = report.get(0).getAsJsonObject();
        phase.getAsJsonObject("wall_distribution").getAsJsonArray("raw").remove(2);
        assertThrows(IllegalStateException.class, () -> IndexedFontCpuReportValidation.verifyPhases(report, List.of("known"), 3));
        JsonArray changed = actual();
        changed.get(0).getAsJsonObject().getAsJsonObject("wall_distribution").addProperty("p95_ns", -1);
        assertThrows(IllegalStateException.class, () -> IndexedFontCpuReportValidation.verifyPhases(changed, List.of("known"), 3));
        JsonArray summary = actual();
        summary.get(0).getAsJsonObject().addProperty("wall_total_ns", -1);
        assertThrows(IllegalStateException.class, () -> IndexedFontCpuReportValidation.verifyPhases(summary, List.of("known"), 3));
    }

    private static JsonArray actual() {
        JvmPerformanceMeter meter = new JvmPerformanceMeter("known", 3);
        for (int sample = 0; sample < 3; sample++) {
            meter.begin();
            meter.end();
        }
        JsonArray reports = new JsonArray();
        reports.add(meter.result());
        return reports;
    }
}
