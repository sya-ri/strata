package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Verifies owner/overload identity and fail-closed compiler-format decoding without timing assertions.
 */
class CompilerApiInventoryTest {
    @field:TempDir lateinit var directory: Path

    @Test
    fun packagedCapturePreservesAllModuleMembersAndRejectsRepeatedFiles() {
        val source = directory.resolve("fixture.api")
        Files.writeString(source, "public final class fixture/First {\npublic final fun render (I)V\n}\n")
        PackagedEvidenceFixture().use { kit ->
            val result = kit.invoke("CompilerApiInventory", "capture", arrayOf(Map::class.java), mapOf("fixture" to listOf(source))) as Map<*, *>
            assertEquals(setOf("jvm:fixture/First#public final class fixture/First {", "jvm:fixture/First#public final fun render (I)V"), result["fixture"])
            assertFails { kit.invoke("CompilerApiInventory", "capture", arrayOf(Map::class.java), mapOf("fixture" to listOf(source, source))) }
            assertFails { kit.invoke("CompilerApiInventory", "capture", arrayOf(Map::class.java), emptyMap<String, List<Path>>()) }
        }
    }

    @Test
    fun jvmOverloadsAndIdenticalMethodsInDifferentOwnersRemainDistinct() {
        val symbols =
            CompilerApiInventory.jvm(
                listOf(
                    "// Verified JVM compiler fixture",
                    "@fixture.Marker",
                    "public final class fixture/First {",
                    "public final fun render (I)V",
                    "public final fun render (J)V",
                    "}",
                    "public abstract interface class fixture/Second {",
                    "public final fun render (I)V",
                    "}",
                ),
            )
        assertEquals(5, symbols.size)
        assertEquals(3, symbols.count { "#public final fun render " in it })
    }

    @Test
    fun klibSignaturesRetainOwnersAttributesAndOverloads() {
        val symbols =
            CompilerApiInventory.klib(
                listOf(
                    "// Klib ABI Dump",
                    "final class fixture/First { // fixture/First|null[0]",
                    "final fun render(kotlin/Int) // fixture/First.render|render(kotlin.Int){}[0]",
                    "final fun render(kotlin/Long) // fixture/First.render|render(kotlin.Long){}[0]",
                    "}",
                    "final fun fixture/Second.render(kotlin/Int) // fixture/Second.render|render(kotlin.Int){}[0]",
                ),
            )
        assertEquals(4, symbols.size)
    }

    @Test
    fun missingOwnersDuplicateMembersAndUnknownFormatsRejectRegistration() {
        assertFails { CompilerApiInventory.jvm(listOf("public final fun render (I)V")) }
        assertFails { CompilerApiInventory.jvm(listOf("public final class fixture/First {")) }
        assertFails { CompilerApiInventory.jvm(listOf("public final class fixture/First {", "public final fun render (I)V", "public final fun render (I)V", "}")) }
        assertFails { CompilerApiInventory.jvm(listOf("public final class fixture/First {", "unknown member", "}")) }
        assertFails { CompilerApiInventory.klib(listOf("final fun fixture/missingSignature()")) }
        assertFails { CompilerApiInventory.klib(emptyList()) }
    }
}
