@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionBinding
import dev.s7a.strata.projection.ProjectionScope
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Shared JVM/JavaScript proof distinguishes detached fixed values from arbitrary callbacks and scope use.
 */
internal class ProjectionFixedValueTest {
    @Test
    fun fixedValueNeverInvokesScopeOrRetainsAnExternalEncoder() {
        val value = ProjectionValue.Sequence(listOf(ProjectionValue.Integer(3)))
        val projection = DeclarationProjection.fixed(TYPE, value, inputEnabled = false)
        assertSame(value, projection.fixedValue)
        assertFalse(projection.inputEnabled)
        assertSame(value, projection.encode(UnusedScope))
        assertSame(value, projection.encode(UnusedScope))
    }

    @Test
    fun ordinaryConstructorCallsTheLiveEncoderEvenWhenItsPreviousValueWasEqual() {
        var current = 1L
        var calls = 0
        val projection = DeclarationProjection(TYPE, Unit) { _, _ -> calls++; ProjectionValue.Integer(current) }
        assertNull(projection.fixedValue)
        assertEquals(ProjectionValue.Integer(1), projection.encode(UnusedScope))
        assertEquals(ProjectionValue.Integer(1), projection.encode(UnusedScope))
        current = 2
        assertEquals(ProjectionValue.Integer(2), projection.encode(UnusedScope))
        assertEquals(3, calls)
    }

    private object UnusedScope : ProjectionScope {
        override fun action(action: ProjectionAction<*>, key: ProjectionValue): Long = error("Unexpected action")
        override fun <T : Any> binding(binding: ProjectionBinding<T>): ProjectionValue = error("Unexpected binding")
        override fun image(image: DrawImage): ProjectionValue = error("Unexpected image")
        override fun text(text: UiText): ProjectionValue = error("Unexpected text")
        override fun requireType(type: ProjectionType): Unit = error("Unexpected capability")
    }

    private companion object {
        val TYPE = ProjectionType(ResourceId("test", "fixed_projection"))
    }
}
