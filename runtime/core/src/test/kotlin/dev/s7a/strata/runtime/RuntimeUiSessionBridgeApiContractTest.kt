package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.spi.RuntimeTextInputFocus
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.annotation.ElementType
import java.lang.annotation.Target
import java.lang.reflect.Modifier

/**
 * Verifies the intentionally narrow JVM-visible runtime bridge surface.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RuntimeUiSessionBridgeApiContractTest {
    @Test
    fun frameAndSessionExposeOnlyTheirReadOnlyContracts() {
        assertInterfaceSurface(
            RuntimeUiFrame::class.java,
            listOf("getSize", "getDrawCommands", "getSemantics"),
        )
        assertInterfaceSurface(
            RuntimeUiSession::class.java,
            listOf("attach", "detach", "frame", "frame", "dispatchPointer", "dispatchKeyboard", "dispatchTextInput", "getTextInputFocus", "close"),
        )
    }

    @Test
    fun permittedImplementationsArePrivateAndNotConstructiblePublicly() {
        listOf(RuntimeUiFrame::class.java, RuntimeUiSession::class.java).forEach { type ->
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
        }
    }

    @Test
    fun textInputFocusTokensExposeNoApplicationReferencesOrPublicConstruction() {
        val token = RuntimeTextInputFocus::class.java
        assertTrue(Modifier.isFinal(token.modifiers))
        assertTrue(token.declaredFields.all { Modifier.isStatic(it.modifiers) })
        assertTrue(token.declaredConstructors.none { Modifier.isPublic(it.modifiers) && it.isSynthetic.not() })
    }

    @Test
    fun factoryHasOnePublicStaticNonSyntheticSignatureWithoutInternalTypes() {
        val factory = Class.forName("dev.s7a.strata.runtime.spi.RuntimeUiSessionFactory")
        val methods =
            factory.declaredMethods.filter { method ->
                Modifier.isPublic(method.modifiers) && method.isSynthetic.not()
            }
        assertEquals(1, methods.size)
        val method = methods.single()
        assertEquals("createRuntimeUiSession", method.name)
        assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals(RuntimeUiSession::class.java, method.returnType)
        assertEquals(
            listOf(Function0::class.java),
            method.parameterTypes.toList(),
        )
        val descriptor = method.toGenericString()
        listOf(
            "dev.s7a.strata.runtime.UiSession",
            "dev.s7a.strata.runtime.UiFrame",
            "dev.s7a.strata.runtime.UiSessionState",
            "dev.s7a.strata.runtime.UiTaskFailureDecision",
            "kotlinx.coroutines",
        ).forEach { forbidden ->
            assertFalse(descriptor.contains(forbidden), descriptor)
        }
    }

    @Test
    fun annotationTargetIsExactlyClassAndFunction() {
        val target = InternalStrataRuntimeApi::class.java.getAnnotation(Target::class.java)
        assertEquals(
            setOf(ElementType.TYPE, ElementType.METHOD),
            target.value.toSet(),
        )
    }

    private fun assertInterfaceSurface(
        type: Class<*>,
        expectedMethods: List<String>,
    ) {
        assertTrue(type.isSealed)
        assertTrue(type.isInterface)
        assertTrue(type.declaredConstructors.isEmpty())
        assertTrue(type.declaredFields.isEmpty())
        assertTrue(type.declaredClasses.isEmpty())
        val methods = type.declaredMethods
        assertEquals(expectedMethods.size, methods.size)
        assertEquals(expectedMethods.sorted(), methods.map { method -> method.name }.sorted())
        methods.forEach { method ->
            assertTrue(Modifier.isPublic(method.modifiers))
            assertTrue(Modifier.isAbstract(method.modifiers))
            assertFalse(method.isSynthetic)
        }
        val orderedMethods = methods.sortedBy { method -> method.name }
        if (type == RuntimeUiFrame::class.java) {
            val drawCommands = orderedMethods[0]
            val semantics = orderedMethods[1]
            val size = orderedMethods[2]
            assertEquals(IntSize::class.java, size.returnType)
            assertEquals(0, size.parameterCount)
            assertEquals(List::class.java, drawCommands.returnType)
            assertEquals(0, drawCommands.parameterCount)
            assertEquals(List::class.java, semantics.returnType)
            assertEquals(0, semantics.parameterCount)
        } else {
            val byName = methods.associateBy { method -> method.name }
            val attach = byName.getValue("attach")
            val close = byName.getValue("close")
            val detach = byName.getValue("detach")
            val frames = methods.groupBy { method -> method.name }.getValue("frame")
            val textInputFocus = byName.getValue("getTextInputFocus")
            assertEquals(RuntimeTextInputFocus::class.java, textInputFocus.returnType)
            assertEquals(0, textInputFocus.parameterCount)
            assertEquals(Void.TYPE, attach.returnType)
            assertEquals(0, attach.parameterCount)
            assertEquals(Void.TYPE, detach.returnType)
            assertEquals(0, detach.parameterCount)
            assertTrue(frames.all { method -> method.returnType == RuntimeUiFrame::class.java })
            assertEquals(
                setOf(
                    listOf(Constraints::class.java),
                    listOf(Constraints::class.java, FrameTime::class.java),
                ),
                frames.map { method -> method.parameterTypes.toList() }.toSet(),
            )
            mapOf(
                "dispatchKeyboard" to KeyboardEvent::class.java,
                "dispatchPointer" to PointerEvent::class.java,
                "dispatchTextInput" to TextInputEvent::class.java,
            ).forEach { (name, parameter) ->
                val input = byName.getValue(name)
                assertEquals(InputResult::class.java, input.returnType)
                assertEquals(listOf(parameter), input.parameterTypes.toList())
            }
            assertEquals(Void.TYPE, close.returnType)
            assertEquals(0, close.parameterCount)
        }
    }
}
