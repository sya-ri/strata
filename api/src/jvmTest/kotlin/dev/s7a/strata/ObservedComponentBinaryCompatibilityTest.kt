@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.buildComponentTree
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.StateObserverNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.text.UiText
import java.lang.invoke.MethodType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.lang.reflect.Modifier as JavaModifier

/**
 * Verifies the frozen JVM linkage contract and forwarding behavior until bridge removal in 1.0.0.
 */
internal class ObservedComponentBinaryCompatibilityTest {
    @Test
    fun frozenJvmDescriptorsRemainPublicAndCurrentOverloadsAreNotDeprecated() {
        val legacy = Class.forName("dev.s7a.strata.component.ObservedComponentsKt")
        val descriptors =
            checkNotNull(javaClass.getResourceAsStream("/observed-component-legacy-methods.txt")).bufferedReader().use { reader ->
                reader.readLines().filter { it.startsWith("#").not() && it.isNotBlank() }
            }
        descriptors.forEach { descriptor ->
            val name = descriptor.substringBefore(' ')
            val type = MethodType.fromMethodDescriptorString(descriptor.substringAfter(' '), javaClass.classLoader)
            val method = legacy.getDeclaredMethod(name, *type.parameterArray())
            assertEquals(type.returnType(), method.returnType, descriptor)
            assertTrue(JavaModifier.isPublic(method.modifiers) && JavaModifier.isStatic(method.modifiers), descriptor)
            assertTrue(method.isSynthetic, descriptor)
            val component = name.substringBefore("State")
            val current = Class.forName("dev.s7a.strata.component.Observed${component}ComponentsKt").getDeclaredMethod(name, *type.parameterArray())
            assertEquals(method.returnType, current.returnType, descriptor)
            if (name.endsWith($$"$default").not()) {
                val deprecation = assertNotNull(method.getAnnotation(Deprecated::class.java))
                assertEquals(DeprecationLevel.HIDDEN, deprecation.level)
                assertTrue(deprecation.message.contains("removal in 1.0.0"))
                assertTrue(deprecation.replaceWith.expression.startsWith("$component("))
                assertEquals(null, current.getAnnotation(Deprecated::class.java))
            }
        }
    }

    @Test
    fun oldMethodAndDefaultHelperForwardSourcesAndSiblingIdentity() {
        val legacy = Class.forName("dev.s7a.strata.component.ObservedComponentsKt")
        val method =
            legacy.getDeclaredMethod(
                "ButtonStatecffa97b99dc2_1",
                UiScope::class.java,
                StateSource::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Modifier::class.java,
                ElementKey::class.java,
            )
        val source = StateSource<UiText> { error("Constructing a declaration must not subscribe.") }
        val key = ElementKey("legacy-button")
        val explicit = buildComponentTree { method.invoke(null, this, source, 180, false, Modifier, key) }
        assertEquals(ElementIdentity.Keyed(key), explicit.identity)
        val explicitNode = explicit.type.createErased(explicit) as StateObserverNode
        assertEquals(listOf(source), explicitNode.observedSources)

        val default =
            legacy.getDeclaredMethod(
                $$"ButtonStatecffa97b99dc2_1$default",
                *method.parameterTypes,
                Int::class.javaPrimitiveType,
                Any::class.java,
            )
        val defaults = buildComponentTree { default.invoke(null, this, source, 0, false, null, null, 30, null) }
        assertEquals(ElementIdentity.Positional, defaults.identity)
        val defaultNode = defaults.type.createErased(defaults) as StateObserverNode
        assertEquals(listOf(source), defaultNode.observedSources)
    }
}
