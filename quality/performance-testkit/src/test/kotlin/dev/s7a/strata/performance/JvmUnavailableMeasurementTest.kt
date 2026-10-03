package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Preserves unavailable nested distributions without accepting missing fields or malformed ancestors.
 */
class JvmUnavailableMeasurementTest {
    @field:TempDir lateinit var directory: Path

    @Test
    fun nullAtEveryDistributionDepthRemainsUnavailable() {
        PackagedEvidenceFixture().use { kit ->
            (0..2).forEach { depth -> verifyNullDepth(kit, depth) }
            val fixture = ReportEvidenceFixtureData(directory.resolve("available"), kit)
            val paths = fixture.create()
            paths.forEach { path -> fixture.mutate(path) { it.arrayField("phases")[0].asJsonObject.add("frame", distribution()) } }
            val result = fixture.summarize(paths, listOf("frame", "cpu", "p50_ns"))
            assertEquals(
                2.0,
                result
                    .arrayField("phases")
                    .single()
                    .asJsonObject
                    .objectField("metrics")
                    .get("p50_ms")
                    .asDouble,
            )
        }
    }

    private fun verifyNullDepth(
        kit: PackagedEvidenceFixture,
        depth: Int,
    ) {
        val fixture = ReportEvidenceFixtureData(directory.resolve("null-$depth"), kit)
        val paths = fixture.create()
        paths.forEach { path ->
            fixture.mutate(path) { report -> report.arrayField("phases")[0].asJsonObject.add("frame", distribution()) }
        }
        fixture.mutate(paths[1]) { report ->
            val phase = report.arrayField("phases")[0].asJsonObject
            when (depth) {
                0 -> phase.add("frame", JsonNull.INSTANCE)
                1 -> phase.objectField("frame").add("cpu", JsonNull.INSTANCE)
                2 -> phase.objectField("frame").objectField("cpu").add("p50_ns", JsonNull.INSTANCE)
            }
        }
        val result = fixture.summarize(paths, listOf("frame", "cpu", "p50_ns"))
        assertTrue(
            result
                .arrayField("phases")
                .single()
                .asJsonObject
                .objectField("metrics")
                .get("p50_ms")
                .isJsonNull,
        )
    }

    @Test
    fun missingOrNonObjectAncestorsCannotMasqueradeAsUnavailable() {
        PackagedEvidenceFixture().use { kit ->
            listOf(JsonObject(), JsonArray(), JsonPrimitive(1), JsonPrimitive(true), JsonPrimitive("unavailable")).forEachIndexed { index, invalid ->
                val fixture = ReportEvidenceFixtureData(directory.resolve("invalid-$index"), kit)
                val paths = fixture.create()
                paths.forEach { path -> fixture.mutate(path) { it.arrayField("phases")[0].asJsonObject.add("frame", distribution()) } }
                fixture.mutate(paths[1]) { it.arrayField("phases")[0].asJsonObject.add("frame", invalid) }
                assertFails { fixture.summarize(paths, listOf("frame", "cpu", "p50_ns")) }
            }
        }
    }

    private fun distribution(): JsonObject =
        JsonObject().apply {
            add("cpu", JsonObject().apply { addProperty("p50_ns", 2_000_000) })
        }
}
