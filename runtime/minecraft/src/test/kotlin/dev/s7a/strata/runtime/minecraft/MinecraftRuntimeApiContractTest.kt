package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Verifies the intentionally minimal JVM-visible common Minecraft runtime surface.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftRuntimeApiContractTest {
    @Test
    fun sealedContractsExposeOnlyTheirLockedMethods() {
        val booleanType = checkNotNull(Boolean::class.javaPrimitiveType)
        assertInterfaceSurface(
            type = MinecraftScreenDefinition::class.java,
            expectedMethods = setOf("getTitle", "getPausesGame"),
        )
        assertInterfaceSurface(
            type = MinecraftUiHost::class.java,
            expectedMethods = setOf("attach", "detach", "frame", "dispatchPointer", "close"),
        )
        assertEquals(
            setOf(UiText::class.java, booleanType),
            MinecraftScreenDefinition::class.java.declaredMethods
                .map { method -> method.returnType }
                .toSet(),
        )
    }

    @Test
    fun implementationsHaveNoPublicConstructionOrContentAccess() {
        listOf(MinecraftScreenDefinition::class.java, MinecraftUiHost::class.java).forEach { type ->
            val implementations = type.permittedSubclasses.toList()
            assertEquals(1, implementations.size)
            val implementation = implementations.single()
            assertFalse(Modifier.isPublic(implementation.modifiers))
            assertFalse(Modifier.isProtected(implementation.modifiers))
            assertTrue(
                implementation.declaredConstructors.none { constructor ->
                    Modifier.isPublic(constructor.modifiers) && constructor.isSynthetic.not()
                },
            )
            assertTrue(
                implementation.declaredMethods.none { method ->
                    method.name.contains("content", ignoreCase = true)
                },
            )
        }

        val booleanType = checkNotNull(Boolean::class.javaPrimitiveType)
        val definitionImplementation = MinecraftScreenDefinition::class.java.permittedSubclasses.single()
        val definitionFields = definitionImplementation.declaredFields.filter { field -> Modifier.isStatic(field.modifiers).not() }
        assertEquals(3, definitionFields.size)
        assertEquals(
            setOf(UiText::class.java, booleanType, Function0::class.java),
            definitionFields.map { field -> field.type }.toSet(),
        )
        assertTrue(definitionFields.all { field -> Modifier.isPrivate(field.modifiers) })
        assertEquals(1, definitionFields.count { field -> field.type == Function0::class.java })

        val hostImplementation = MinecraftUiHost::class.java.permittedSubclasses.single()
        val hostFields = hostImplementation.declaredFields.filter { field -> Modifier.isStatic(field.modifiers).not() }
        assertEquals(listOf(RuntimeUiSession::class.java), hostFields.map { field -> field.type })
        assertTrue(hostFields.all { field -> Modifier.isPrivate(field.modifiers) })
    }

    @Test
    fun facadeContainsExactlyTheTwoTypedFactories() {
        val booleanType = checkNotNull(Boolean::class.javaPrimitiveType)
        val factory = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftRuntimeFactories")
        val methods =
            factory.declaredMethods.filter { method ->
                Modifier.isPublic(method.modifiers) && method.isSynthetic.not()
            }
        assertEquals(2, methods.size)
        val definitionFactory =
            assertFactory(
                methods = methods,
                name = "createMinecraftScreenDefinition",
                parameters = listOf(UiText::class.java, booleanType, Function0::class.java),
                returnType = MinecraftScreenDefinition::class.java,
            )
        val hostFactory =
            assertFactory(
                methods = methods,
                name = "createMinecraftUiHost",
                parameters = listOf(MinecraftScreenDefinition::class.java),
                returnType = MinecraftUiHost::class.java,
            )
        val descriptors = listOf(definitionFactory, hostFactory).joinToString(separator = "\n") { method -> method.toGenericString() }
        listOf(
            "kotlinx.coroutines",
            "net.minecraft",
            "net.fabricmc",
            "RuntimeUiSession",
            "UiSession",
        ).forEach { forbidden -> assertFalse(descriptors.contains(forbidden), descriptors) }
    }

    @Test
    fun hostMethodsHaveExactDescriptors() {
        val methods = MinecraftUiHost::class.java.declaredMethods.associateBy { method -> method.name }
        assertMethod(methods.getValue("attach"), emptyList(), Void.TYPE)
        assertMethod(methods.getValue("detach"), emptyList(), Void.TYPE)
        assertMethod(methods.getValue("close"), emptyList(), Void.TYPE)
        assertMethod(methods.getValue("frame"), listOf(IntSize::class.java), RuntimeUiFrame::class.java)
        assertMethod(methods.getValue("dispatchPointer"), listOf(PointerEvent::class.java), InputResult::class.java)
    }

    private fun assertInterfaceSurface(
        type: Class<*>,
        expectedMethods: Set<String>,
    ) {
        assertTrue(type.isInterface)
        assertTrue(type.isSealed)
        assertTrue(type.declaredConstructors.isEmpty())
        assertTrue(type.declaredFields.isEmpty())
        assertTrue(type.declaredClasses.isEmpty())
        assertEquals(expectedMethods, type.declaredMethods.map { method -> method.name }.toSet())
        type.declaredMethods.forEach { method ->
            assertTrue(Modifier.isPublic(method.modifiers))
            assertTrue(Modifier.isAbstract(method.modifiers))
            assertFalse(method.isSynthetic)
        }
    }

    private fun assertFactory(
        methods: List<Method>,
        name: String,
        parameters: List<Class<*>>,
        returnType: Class<*>,
    ): Method {
        val method = methods.single { candidate -> candidate.name == name }
        assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals(parameters, method.parameterTypes.toList())
        assertEquals(returnType, method.returnType)
        return method
    }

    private fun assertMethod(
        method: Method,
        parameters: List<Class<*>>,
        returnType: Class<*>,
    ) {
        assertEquals(parameters, method.parameterTypes.toList())
        assertEquals(returnType, method.returnType)
    }
}
