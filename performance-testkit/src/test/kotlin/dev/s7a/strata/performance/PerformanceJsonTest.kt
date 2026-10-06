package dev.s7a.strata.performance

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Evidence publication must preserve the collector binding and cannot replace an earlier acceptance receipt.
 */
class PerformanceJsonTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun refusesReplacementAndDetachesTheActualCollectorReceipt() {
        val report = JsonObject().apply { addProperty("status", "passed") }
        val destination = temporary.resolve("nested/receipt.json")
        PerformanceJson.writeNew(destination, report)
        val original = Files.readString(destination)
        assertFalse(report.has("collector_identity"))
        assertEquals(PerformanceJson.collectorIdentity(), JsonParser.parseString(original).asJsonObject.getAsJsonObject("collector_identity"))
        report.addProperty("status", "replaced")
        assertFailsWith<FileAlreadyExistsException> { PerformanceJson.writeNew(destination, report) }
        assertEquals(original, Files.readString(destination))
    }
}
