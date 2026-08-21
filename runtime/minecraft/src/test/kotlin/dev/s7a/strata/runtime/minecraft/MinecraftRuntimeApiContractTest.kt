package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import dev.s7a.strata.modifier.Modifier as UiModifier

/**
 * Verifies the intentionally minimal JVM-visible common Minecraft runtime surface.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftRuntimeApiContractTest {
    @Test
    fun sealedContractsExposeOnlyTheirLockedMethods() {
        assertInterfaceSurface(MinecraftScreenDefinition::class.java, setOf("close"))
        assertInterfaceSurface(
            MinecraftUiHost::class.java,
            setOf("getTitle", "getPausesGame", "attach", "detach", "frame", "dispatchPointer", "dispatchKeyboard", "dispatchTextInput", "close"),
        )
        assertInterfaceSurface(
            MinecraftUiContext::class.java,
            setOf("MenuBackground", "ContainerBackground", "Slot", "Text", "TextField", "Button", "Scroll"),
            allowDefaultImpls = true,
        )
        assertInterfaceSurface(MinecraftUiProfile::class.java, emptySet())
        assertInterfaceSurface(MinecraftTextFieldState::class.java, setOf("getValue", "setValue", "getMaxLength"))
        assertInterfaceSurface(
            MinecraftUiProfileBuilder::class.java,
            setOf(
                "menuBackground",
                "containerBackground",
                "slotHighlightBack",
                "slotHighlightFront",
                "listBackground",
                "listHeaderSeparator",
                "listFooterSeparator",
                "scrollbarBackground",
                "scrollbarThumb",
                "textFieldNormal",
                "textFieldHighlighted",
                "printableAsciiGlyph",
                "buttonNormal",
                "buttonHighlighted",
                "buttonDisabled",
            ),
        )
    }

    @Test
    fun facadeContainsExactlyTheTypedFactoriesAndLiteralTitleOverload() {
        val factory = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftRuntimeFactories")
        val methods = factory.declaredMethods.filter { method -> Modifier.isPublic(method.modifiers) && method.isSynthetic.not() }
        assertEquals(5, methods.size)
        assertFactory(
            methods,
            "createMinecraftScreenDefinition",
            listOf(String::class.java, checkNotNull(Boolean::class.javaPrimitiveType), Function1::class.java),
            MinecraftScreenDefinition::class.java,
        )
        assertFactory(
            methods,
            "createMinecraftScreenDefinition",
            listOf(UiText::class.java, checkNotNull(Boolean::class.javaPrimitiveType), Function1::class.java),
            MinecraftScreenDefinition::class.java,
        )
        assertFactory(
            methods,
            "createMinecraftTextFieldState",
            listOf(String::class.java, checkNotNull(Int::class.javaPrimitiveType)),
            MinecraftTextFieldState::class.java,
        )
        assertFactory(
            methods,
            "createMinecraftUiHost",
            listOf(MinecraftScreenDefinition::class.java, MinecraftUiProfile::class.java),
            MinecraftUiHost::class.java,
        )
        assertFactory(
            methods,
            "createMinecraftUiProfile",
            listOf(Function1::class.java),
            MinecraftUiProfile::class.java,
        )
        methods.forEach { method ->
            val descriptor = method.toGenericString()
            assertFalse(descriptor.contains("RuntimeUiSession"), descriptor)
            assertFalse(descriptor.contains("UiSession"), descriptor)
            assertFalse(descriptor.contains("kotlinx.coroutines"), descriptor)
        }
    }

    @Test
    fun hostMethodsHaveExactDescriptors() {
        val methods = MinecraftUiHost::class.java.declaredMethods.associateBy { method -> method.name }
        assertMethod(methods.getValue("getTitle"), emptyList(), UiText::class.java)
        assertMethod(methods.getValue("getPausesGame"), emptyList(), checkNotNull(Boolean::class.javaPrimitiveType))
        assertMethod(methods.getValue("attach"), emptyList(), Void.TYPE)
        assertMethod(methods.getValue("detach"), emptyList(), Void.TYPE)
        assertMethod(methods.getValue("close"), emptyList(), Void.TYPE)
        assertMethod(methods.getValue("frame"), listOf(IntSize::class.java), RuntimeUiFrame::class.java)
        assertMethod(methods.getValue("dispatchPointer"), listOf(PointerEvent::class.java), InputResult::class.java)
        assertMethod(methods.getValue("dispatchKeyboard"), listOf(KeyboardEvent::class.java), InputResult::class.java)
        assertMethod(methods.getValue("dispatchTextInput"), listOf(TextInputEvent::class.java), InputResult::class.java)

        assertContextMethodDescriptors()
    }

    private fun assertContextMethodDescriptors() {
        val contextMethods = MinecraftUiContext::class.java.declaredMethods.toList()
        assertTextDescriptors(contextMethods)
        assertContainerDescriptors(contextMethods)
        assertTextFieldDescriptor(contextMethods)
        assertButtonDescriptors(contextMethods)
        assertScrollDescriptor(contextMethods)
        contextMethods.forEach { method -> assertEquals(Void.TYPE, method.returnType) }
    }

    private fun assertContainerDescriptors(contextMethods: List<Method>) {
        assertEquals(
            setOf(
                listOf(
                    UiScope::class.java,
                    checkNotNull(Int::class.javaPrimitiveType),
                    UiModifier::class.java,
                    ElementKey::class.java,
                ),
            ),
            contextMethods
                .filter { method -> method.name == ContextMethodName.ContainerBackground.jvmName }
                .map { method -> method.parameterTypes.toList() }
                .toSet(),
        )
        assertEquals(
            setOf(
                listOf(
                    UiScope::class.java,
                    checkNotNull(Boolean::class.javaPrimitiveType),
                    UiModifier::class.java,
                    ElementKey::class.java,
                    Function1::class.java,
                ),
            ),
            contextMethods
                .filter { method -> method.name == ContextMethodName.Slot.jvmName }
                .map { method -> method.parameterTypes.toList() }
                .toSet(),
        )
    }

    private fun assertTextDescriptors(contextMethods: List<Method>) {
        assertEquals(
            setOf(
                listOf(UiScope::class.java, UiText::class.java, MinecraftTextStyle::class.java, UiModifier::class.java, ElementKey::class.java),
                listOf(UiScope::class.java, String::class.java, MinecraftTextStyle::class.java, UiModifier::class.java, ElementKey::class.java),
            ),
            contextMethods
                .filter { method -> method.name == ContextMethodName.Text.jvmName }
                .map { method -> method.parameterTypes.toList() }
                .toSet(),
        )
    }

    private fun assertTextFieldDescriptor(contextMethods: List<Method>) {
        assertEquals(
            setOf(
                listOf(
                    UiScope::class.java,
                    MinecraftTextFieldState::class.java,
                    checkNotNull(Boolean::class.javaPrimitiveType),
                    UiModifier::class.java,
                    ElementKey::class.java,
                ),
            ),
            contextMethods
                .filter { method -> method.name == ContextMethodName.TextField.jvmName }
                .map { method -> method.parameterTypes.toList() }
                .toSet(),
        )
    }

    private fun assertButtonDescriptors(contextMethods: List<Method>) {
        assertEquals(
            setOf(
                listOf(
                    UiScope::class.java,
                    UiText::class.java,
                    checkNotNull(Int::class.javaPrimitiveType),
                    checkNotNull(Boolean::class.javaPrimitiveType),
                    UiModifier::class.java,
                    ElementKey::class.java,
                ),
                listOf(
                    UiScope::class.java,
                    String::class.java,
                    checkNotNull(Int::class.javaPrimitiveType),
                    checkNotNull(Boolean::class.javaPrimitiveType),
                    UiModifier::class.java,
                    ElementKey::class.java,
                ),
            ),
            contextMethods
                .filter { method -> method.name == ContextMethodName.Button.jvmName }
                .map { method -> method.parameterTypes.toList() }
                .toSet(),
        )
    }

    private fun assertScrollDescriptor(contextMethods: List<Method>) {
        assertEquals(
            setOf(
                listOf(
                    UiScope::class.java,
                    UiModifier::class.java,
                    ElementKey::class.java,
                    checkNotNull(Int::class.javaPrimitiveType),
                    Function1::class.java,
                ),
            ),
            contextMethods
                .filter { method -> method.name == ContextMethodName.Scroll.jvmName }
                .map { method -> method.parameterTypes.toList() }
                .toSet(),
        )
    }

    @Test
    fun privateImplementationFacadesDoNotLeakAccessors() {
        listOf(
            "dev.s7a.strata.runtime.minecraft.MinecraftMenuBackgroundElement",
            "dev.s7a.strata.runtime.minecraft.MinecraftTextElement",
            "dev.s7a.strata.runtime.minecraft.MinecraftHostImplementation",
            "dev.s7a.strata.runtime.minecraft.MinecraftDefinitionImplementation",
            "dev.s7a.strata.runtime.minecraft.MinecraftProfileImplementation",
            "dev.s7a.strata.runtime.minecraft.MinecraftTextRun",
            "dev.s7a.strata.runtime.minecraft.MinecraftPointerButtonElement",
            "dev.s7a.strata.runtime.minecraft.MinecraftScrollElement",
            "dev.s7a.strata.runtime.minecraft.MinecraftTextFieldElement",
            "dev.s7a.strata.runtime.minecraft.MinecraftContainerBackgroundElement",
            "dev.s7a.strata.runtime.minecraft.MinecraftSlotElement",
        ).forEach { name ->
            val implementation = Class.forName(name)
            assertTrue(implementation.declaredMethods.none { method -> method.name.startsWith("access$") })
        }
        val permitted =
            MinecraftNineSliceCenterMode::class.java.permittedSubclasses
                .toSet()
        assertEquals(
            setOf(
                MinecraftNineSliceCenterMode.Tiled::class.java,
                MinecraftNineSliceCenterMode.Stretched::class.java,
            ),
            permitted,
        )
    }

    @Test
    fun pointerButtonCarriersHaveNoNonsyntheticJvmSurface() {
        val button = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftPointerButtonElement")
        assertFalse(Modifier.isPublic(button.modifiers), button.name)
        assertFalse(Modifier.isProtected(button.modifiers), button.name)
        val carriers =
            listOf(
                button,
                Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftPointerButtonElementKt"),
                Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftPointerButtonElement\$Companion"),
            )
        carriers.forEach { type ->
            assertTrue(type.declaredMethods.none { method -> method.name.startsWith("access$") }, type.name)
            type.declaredConstructors
                .filter { constructor -> Modifier.isPublic(constructor.modifiers) }
                .forEach { constructor -> assertTrue(constructor.isSynthetic, constructor.toString()) }
            type.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
                .forEach { method -> assertTrue(method.isSynthetic, method.toString()) }
            type.declaredFields
                .filter { field -> Modifier.isPublic(field.modifiers) }
                .forEach { field ->
                    assertEquals("Companion", field.name)
                    assertTrue(Modifier.isStatic(field.modifiers))
                    assertTrue(Modifier.isFinal(field.modifiers))
                }
        }
    }

    @Test
    fun internalJavaCarriersExposeNoNonsyntheticPublicEntryPoint() {
        val carriers =
            listOf(
                TransferredMinecraftDefinition::class.java,
                MinecraftGlyphSnapshot::class.java,
                MinecraftButtonSpriteSnapshot::class.java,
                MinecraftTextRun::class.java,
            )
        carriers
            .flatMap { type -> listOf(type) + type.declaredClasses }
            .filter { type -> Modifier.isPublic(type.modifiers) }
            .forEach { type ->
                type.declaredConstructors
                    .filter { constructor -> Modifier.isPublic(constructor.modifiers) }
                    .forEach { constructor -> assertTrue(constructor.isSynthetic, constructor.toString()) }
                type.declaredMethods
                    .filter { method -> Modifier.isPublic(method.modifiers) }
                    .forEach { method -> assertTrue(method.isSynthetic, method.toString()) }
                type.declaredFields
                    .filter { field -> Modifier.isPublic(field.modifiers) }
                    .forEach { field ->
                        assertEquals("Companion", field.name)
                        assertTrue(Modifier.isStatic(field.modifiers))
                        assertTrue(Modifier.isFinal(field.modifiers))
                    }
            }

        listOf(
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftMenuBackgroundElement"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextElement"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftScrollElement"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextFieldElement"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftContainerBackgroundElement"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftSlotElement"),
        ).forEach { type ->
            assertFalse(Modifier.isPublic(type.modifiers), type.name)
            assertFalse(Modifier.isProtected(type.modifiers), type.name)
        }

        listOf(
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftMenuBackgroundElementKt"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextElementKt"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftScrollElementKt"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextFieldElementKt"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftContainerBackgroundElementKt"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftSlotElementKt"),
        ).flatMap { type -> type.declaredMethods.toList() }.forEach { method ->
            assertTrue(method.isSynthetic, method.toString())
        }
    }

    @Test
    fun privateImplementationsKeepStatePrivateAndNoContentAccessor() {
        val definition =
            createMinecraftScreenDefinition(UiText.Literal("surface")) {
                buildUi { MenuBackground() }
            }
        val profile = MinecraftProfileFixture.create()
        var context: MinecraftUiContext? = null
        val host =
            createMinecraftUiHost(
                createMinecraftScreenDefinition(UiText.Literal("surface")) {
                    context = this
                    buildUi { MenuBackground() }
                },
                profile,
            )
        host.attach()
        val types =
            listOf(
                definition.javaClass,
                profile.javaClass,
                host.javaClass,
                checkNotNull(context).javaClass,
            )
        types.forEach { type ->
            assertFalse(Modifier.isPublic(type.modifiers), type.name)
            assertFalse(Modifier.isProtected(type.modifiers), type.name)
            type.declaredFields
                .filter { field -> Modifier.isStatic(field.modifiers).not() }
                .forEach { field -> assertTrue(Modifier.isPrivate(field.modifiers), "${type.name}.${field.name}") }
            type.declaredConstructors
                .filter { constructor -> constructor.isSynthetic.not() }
                .forEach { constructor -> assertTrue(Modifier.isPrivate(constructor.modifiers), constructor.toString()) }
            assertTrue(type.declaredFields.none { field -> field.type == Function1::class.java })
            assertTrue(type.declaredMethods.none { method -> method.returnType == Function1::class.java })
            assertTrue(type.declaredMethods.none { method -> method.name.startsWith("access$") })
        }
        definition.close()
        host.close()
    }

    @Test
    fun internalEntryPointsAreJvmSynthetic() {
        assertTrue(
            MinecraftDefinitionImplementation::class.java
                .getDeclaredMethod("create", UiText::class.java, Boolean::class.javaPrimitiveType, Function1::class.java)
                .isSynthetic,
        )
        assertTrue(
            MinecraftDefinitionImplementation::class.java
                .getDeclaredMethod("take", MinecraftScreenDefinition::class.java)
                .isSynthetic,
        )
        assertTrue(
            MinecraftHostImplementation::class.java
                .getDeclaredMethod("create", MinecraftScreenDefinition::class.java, MinecraftUiProfile::class.java)
                .isSynthetic,
        )
        assertTrue(
            MinecraftProfileImplementation::class.java
                .getDeclaredMethod("create", Function1::class.java)
                .isSynthetic,
        )
        assertTrue(
            MinecraftProfileImplementation::class.java
                .getDeclaredMethod(
                    "createEvaluator",
                    MinecraftUiProfile::class.java,
                    Function1::class.java,
                ).isSynthetic,
        )
        assertTrue(
            MinecraftProfileImplementation::class.java
                .getDeclaredMethod("releaseEvaluator", Function0::class.java)
                .isSynthetic,
        )
    }

    @Test
    fun nineSliceObjectsHaveOnlyTheirValueMethods() {
        listOf(MinecraftNineSliceCenterMode.Tiled::class.java, MinecraftNineSliceCenterMode.Stretched::class.java)
            .forEach { type ->
                assertTrue(Modifier.isFinal(type.modifiers))
                assertTrue(type.declaredConstructors.single().let { constructor -> Modifier.isPrivate(constructor.modifiers) })
                assertEquals(
                    setOf("equals", "hashCode", "toString"),
                    type.declaredMethods
                        .filter { method -> method.isSynthetic.not() }
                        .map { method -> method.name }
                        .toSet(),
                )
            }
    }

    @Test
    fun newSurfaceDoesNotExposePlatformOrResourceDescriptors() {
        val types =
            listOf(
                MinecraftScreenDefinition::class.java,
                MinecraftUiHost::class.java,
                MinecraftUiContext::class.java,
                MinecraftUiProfile::class.java,
                MinecraftUiProfileBuilder::class.java,
                MinecraftTextFieldState::class.java,
                Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftRuntimeFactories"),
            )
        types.flatMap { type -> type.declaredMethods.toList() }.forEach { method ->
            val descriptor = method.toGenericString()
            assertFalse(descriptor.contains("net.minecraft"), descriptor)
            assertFalse(descriptor.contains("net.fabricmc"), descriptor)
            assertFalse(descriptor.contains("kotlinx.coroutines"), descriptor)
            assertFalse(descriptor.contains("Resource"), descriptor)
        }
    }

    private fun assertInterfaceSurface(
        type: Class<*>,
        expectedMethods: Set<String>,
        allowDefaultImpls: Boolean = false,
    ) {
        assertTrue(type.isInterface)
        assertTrue(type.isSealed)
        val implementation = type.permittedSubclasses.single()
        assertTrue(Modifier.isPrivate(implementation.modifiers), implementation.name)
        assertTrue(type.declaredConstructors.isEmpty())
        assertTrue(type.declaredFields.isEmpty())
        assertTrue(
            allowDefaultImpls || type.declaredClasses.isEmpty(),
            "Unexpected nested types: ${type.name} ${type.declaredClasses.toList()}",
        )
        val declaredMethods = type.declaredMethods.filter { method -> method.isSynthetic.not() }
        assertEquals(expectedMethods, declaredMethods.map { method -> method.name }.toSet())
        declaredMethods.forEach { method ->
            assertTrue(Modifier.isPublic(method.modifiers))
            if (allowDefaultImpls.not()) {
                assertTrue(Modifier.isAbstract(method.modifiers))
            }
            assertFalse(method.isSynthetic)
        }
    }

    private fun assertFactory(
        methods: List<Method>,
        name: String,
        parameters: List<Class<*>>,
        returnType: Class<*>,
    ) {
        val method =
            methods.single { candidate ->
                candidate.name == name && candidate.parameterTypes.toList() == parameters
            }
        assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals(parameters, method.parameterTypes.toList())
        assertEquals(returnType, method.returnType)
    }

    private fun assertMethod(
        method: Method,
        parameters: List<Class<*>>,
        returnType: Class<*>,
    ) {
        assertEquals(parameters, method.parameterTypes.toList())
        assertEquals(returnType, method.returnType)
    }

    private enum class ContextMethodName(
        val jvmName: String,
    ) {
        Text("Text"),
        ContainerBackground("ContainerBackground"),
        Slot("Slot"),
        TextField("TextField"),
        Button("Button"),
        Scroll("Scroll"),
    }
}
