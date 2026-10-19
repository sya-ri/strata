package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.TextShadow
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies complete style transport and explicit schema incompatibility without native resources.
 */
internal class RemoteTextStyleTest {
    @Test
    fun allShadowPoliciesAndArgbBitsSurviveTheBinaryBoundary() {
        val codec = RemoteValueCodec()
        for (shadow in listOf(null, TextShadow.Automatic, TextShadow.Color(ArgbColor(0x80553311.toInt())))) {
            for (disabled in listOf(null, ArgbColor(0x40667788))) {
                val style = TextStyle(ArgbColor(0xc0a0b0d0.toInt()), shadow, disabled)
                assertEquals(style, RemoteProperties.textStyle(codec.decode(codec.encode(RemoteProperties.encode(style)))))
            }
        }
    }

    @Test
    fun truncatedUnknownAndOutOfRangeStyleFieldsAreRejected() {
        val color = ProjectionValue.Integer(-1)
        val absent = ProjectionValue.Absent
        val invalid =
            listOf(
                color,
                RemoteProperties.record(color, absent),
                RemoteProperties.record(ProjectionValue.Integer(Long.MAX_VALUE), absent, absent),
                RemoteProperties.record(color, ProjectionValue.Flag(true), absent),
                RemoteProperties.record(color, absent, RemoteProperties.record(ProjectionValue.Integer(2))),
                RemoteProperties.record(color, absent, RemoteProperties.record(ProjectionValue.Integer(1))),
                RemoteProperties.record(color, absent, RemoteProperties.record(ProjectionValue.Integer(0), color)),
                RemoteProperties.record(color, absent, absent, absent),
            )
        invalid.forEach { value -> assertThrows(IllegalArgumentException::class.java) { RemoteProperties.textStyle(value) } }
    }

    @Test
    fun onlyChangedProfileSchemasAdvanceToVersionTwo() {
        val registry = RemoteRegistry().also(RemoteBuiltins::register)
        val types = registry.types
        for (path in listOf("text", "text_field", "text_area")) {
            val name = ResourceId("strata", "profile/$path")
            assertTrue(ProjectionType(name, 2) in types)
            assertFalse(ProjectionType(name, 1) in types)
            val legacy = RemoteTree(1, listOf(RemoteNode(RemoteDeclaration(1, ProjectionType(name, 1), ProjectionValue.Absent))))
            assertThrows(IllegalArgumentException::class.java) { registry.validateTypes(legacy) }
        }
        assertTrue(ProjectionType(ResourceId("strata", "profile/button"), 1) in types)
    }
}
