package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.render.DrawImage
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
        assertInterfaceSurface(MinecraftUiProfile::class.java, emptySet())
        assertInterfaceSurface(MinecraftAssetId::class.java, setOf("getNamespace", "getPath"))
        assertInterfaceSurface(MinecraftSlotBinding::class.java, setOf("getSource", "getIndex"))
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
        assertEquals(6, methods.size)
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
            "createMinecraftUiHost",
            listOf(MinecraftScreenDefinition::class.java, MinecraftUiProfile::class.java, MinecraftUiPlatform::class.java),
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
    fun slotLocatorFactoryExposesOnlyTypedConstructors() {
        val methods =
            MinecraftSlots::class.java.declaredMethods.filter { method ->
                Modifier.isPublic(method.modifiers) && method.isSynthetic.not()
            }
        assertEquals(setOf("playerInventory", "container", "activeMenu"), methods.map { method -> method.name }.toSet())
        methods.forEach { method ->
            assertTrue(Modifier.isStatic(method.modifiers))
            assertMethod(method, listOf(checkNotNull(Int::class.javaPrimitiveType)), MinecraftSlotBinding::class.java)
        }
        assertEquals(
            setOf(MinecraftSlotSource.PlayerInventory, MinecraftSlotSource.Container, MinecraftSlotSource.ActiveMenu),
            MinecraftSlotSource.entries.toSet(),
        )
    }

    @Test
    fun assetFactoryAndImageScaleExposeOnlyTypedValues() {
        val methods =
            MinecraftAssets::class.java.declaredMethods.filter { method ->
                Modifier.isPublic(method.modifiers) && method.isSynthetic.not()
            }
        val resource = methods.single()
        assertEquals("resource", resource.name)
        assertTrue(Modifier.isStatic(resource.modifiers))
        assertMethod(resource, listOf(String::class.java, String::class.java), MinecraftAssetId::class.java)
        assertEquals(setOf(MinecraftImageScale.Stretch, MinecraftImageScale.Tile), MinecraftImageScale.entries.toSet())
        assertEquals(
            setOf(MinecraftTextStyle.Normal, MinecraftTextStyle.Inactive, MinecraftTextStyle.ContainerLabel, MinecraftTextStyle.TextField),
            MinecraftTextStyle.entries.toSet(),
        )
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

        assertDslFacadeDescriptors()
    }

    private fun assertDslFacadeDescriptors() {
        val componentMethods =
            Class
                .forName("dev.s7a.strata.runtime.minecraft.MinecraftUiComponents")
                .declaredMethods
                .filter { method -> method.isSynthetic.not() }
        val modifierMethods =
            Class
                .forName("dev.s7a.strata.runtime.minecraft.MinecraftUiModifiers")
                .declaredMethods
                .filter { method -> method.isSynthetic.not() }
        assertEquals(12, componentMethods.size)
        assertEquals(4, modifierMethods.size)
        assertBackgroundModifierDescriptors(modifierMethods)
        assertTextDescriptors(componentMethods)
        assertContainerDescriptors(componentMethods)
        assertTextFieldDescriptor(componentMethods)
        assertButtonDescriptors(componentMethods)
        assertScrollDescriptor(componentMethods)
        val playerHead = componentMethods.single { method -> method.name == DslMethodName.PlayerHead.jvmName }
        assertMethod(
            playerHead,
            listOf(
                UiScope::class.java,
                DrawImage::class.java,
                checkNotNull(Int::class.javaPrimitiveType),
                checkNotNull(Boolean::class.javaPrimitiveType),
                UiModifier::class.java,
                ElementKey::class.java,
            ),
            Void.TYPE,
        )
        assertEquals(
            setOf(
                listOf(UiScope::class.java, DrawImage::class.java, IntSize::class.java, UiModifier::class.java, ElementKey::class.java),
                listOf(
                    UiScope::class.java,
                    DrawImage::class.java,
                    IntRect::class.java,
                    IntSize::class.java,
                    UiModifier::class.java,
                    ElementKey::class.java,
                ),
            ),
            componentMethods
                .filter { method -> method.name == DslMethodName.Image.jvmName }
                .map { method -> method.parameterTypes.toList() }
                .toSet(),
        )
        componentMethods.forEach { method ->
            assertTrue(Modifier.isPublic(method.modifiers))
            assertTrue(Modifier.isStatic(method.modifiers))
            assertEquals(Void.TYPE, method.returnType)
        }
        modifierMethods.forEach { method ->
            assertTrue(Modifier.isPublic(method.modifiers))
            assertTrue(Modifier.isStatic(method.modifiers))
            assertEquals(UiModifier::class.java, method.returnType)
        }
    }

    private fun assertBackgroundModifierDescriptors(methods: List<Method>) {
        val menu = methods.single { method -> method.name == DslMethodName.MenuBackground.jvmName }
        assertMethod(menu, listOf(UiModifier::class.java), UiModifier::class.java)
        val container = methods.single { method -> method.name == DslMethodName.ContainerBackground.jvmName }
        assertMethod(
            container,
            listOf(UiModifier::class.java, checkNotNull(Int::class.javaPrimitiveType)),
            UiModifier::class.java,
        )
        val image = methods.filter { method -> method.name == DslMethodName.ImageBackground.jvmName }
        assertEquals(
            setOf(
                listOf(UiModifier::class.java, DrawImage::class.java, MinecraftImageScale::class.java),
                listOf(UiModifier::class.java, DrawImage::class.java, Insets::class.java, MinecraftNineSliceCenterMode::class.java),
            ),
            image.map { method -> method.parameterTypes.toList() }.toSet(),
        )
    }

    private fun assertContainerDescriptors(methods: List<Method>) {
        assertEquals(
            setOf(
                listOf(
                    UiScope::class.java,
                    checkNotNull(Boolean::class.javaPrimitiveType),
                    UiModifier::class.java,
                    ElementKey::class.java,
                    Function1::class.java,
                ),
                listOf(
                    UiScope::class.java,
                    MinecraftSlotBinding::class.java,
                    checkNotNull(Boolean::class.javaPrimitiveType),
                    UiModifier::class.java,
                    ElementKey::class.java,
                ),
            ),
            methods
                .filter { method -> method.name == DslMethodName.Slot.jvmName }
                .map { method -> method.parameterTypes.toList() }
                .toSet(),
        )
    }

    private fun assertTextDescriptors(methods: List<Method>) {
        assertEquals(
            setOf(
                listOf(UiScope::class.java, UiText::class.java, MinecraftTextStyle::class.java, UiModifier::class.java, ElementKey::class.java),
                listOf(UiScope::class.java, String::class.java, MinecraftTextStyle::class.java, UiModifier::class.java, ElementKey::class.java),
            ),
            methods
                .filter { method -> method.name == DslMethodName.Text.jvmName }
                .map { method -> method.parameterTypes.toList() }
                .toSet(),
        )
    }

    private fun assertTextFieldDescriptor(methods: List<Method>) {
        assertEquals(
            setOf(
                listOf(
                    UiScope::class.java,
                    MinecraftTextFieldState::class.java,
                    checkNotNull(Boolean::class.javaPrimitiveType),
                    MinecraftTextStyle::class.java,
                    UiModifier::class.java,
                    ElementKey::class.java,
                ),
                listOf(
                    UiScope::class.java,
                    MinecraftTextFieldState::class.java,
                    IntSize::class.java,
                    checkNotNull(Boolean::class.javaPrimitiveType),
                    MinecraftTextStyle::class.java,
                    UiModifier::class.java,
                    ElementKey::class.java,
                ),
            ),
            methods
                .filter { method -> method.name == DslMethodName.TextField.jvmName }
                .map { method -> method.parameterTypes.toList() }
                .toSet(),
        )
    }

    private fun assertButtonDescriptors(methods: List<Method>) {
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
            methods
                .filter { method -> method.name == DslMethodName.Button.jvmName }
                .map { method -> method.parameterTypes.toList() }
                .toSet(),
        )
    }

    private fun assertScrollDescriptor(methods: List<Method>) {
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
            methods
                .filter { method -> method.name == DslMethodName.Scroll.jvmName }
                .map { method -> method.parameterTypes.toList() }
                .toSet(),
        )
    }

    @Test
    fun privateImplementationFacadesDoNotLeakAccessors() {
        listOf(
            "dev.s7a.strata.runtime.minecraft.MinecraftMenuBackgroundModifier",
            "dev.s7a.strata.runtime.minecraft.MinecraftTextElement",
            "dev.s7a.strata.runtime.minecraft.MinecraftHostImplementation",
            "dev.s7a.strata.runtime.minecraft.MinecraftDefinitionImplementation",
            "dev.s7a.strata.runtime.minecraft.MinecraftProfileImplementation",
            "dev.s7a.strata.runtime.minecraft.MinecraftTextRun",
            "dev.s7a.strata.runtime.minecraft.MinecraftPointerButtonElement",
            "dev.s7a.strata.runtime.minecraft.MinecraftScrollElement",
            "dev.s7a.strata.runtime.minecraft.MinecraftTextFieldElement",
            "dev.s7a.strata.runtime.minecraft.MinecraftContainerBackgroundModifier",
            "dev.s7a.strata.runtime.minecraft.MinecraftSlotElement",
            "dev.s7a.strata.runtime.minecraft.MinecraftPlayerHeadElement",
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
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftMenuBackgroundModifier"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextElement"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftScrollElement"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextFieldElement"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftContainerBackgroundModifier"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftSlotElement"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftPlayerHeadElement"),
        ).forEach { type ->
            assertFalse(Modifier.isPublic(type.modifiers), type.name)
            assertFalse(Modifier.isProtected(type.modifiers), type.name)
        }

        listOf(
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftMenuBackgroundModifierKt"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextElementKt"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftScrollElementKt"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextFieldElementKt"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftContainerBackgroundModifierKt"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftSlotElementKt"),
            Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftPlayerHeadElementKt"),
        ).flatMap { type -> type.declaredMethods.toList() }.forEach { method ->
            assertTrue(method.isSynthetic, method.toString())
        }
    }

    @Test
    fun privateImplementationsKeepStatePrivateAndNoContentAccessor() {
        val definition =
            createMinecraftScreenDefinition(UiText.Literal("surface")) {
                Text("definition")
            }
        val profile = MinecraftProfileFixture.create()
        val host =
            createMinecraftUiHost(
                createMinecraftScreenDefinition(UiText.Literal("surface")) {
                    Text("host")
                },
                profile,
            )
        host.attach()
        val types =
            listOf(
                definition.javaClass,
                profile.javaClass,
                host.javaClass,
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
                .getDeclaredMethod(
                    "create",
                    MinecraftScreenDefinition::class.java,
                    MinecraftUiProfile::class.java,
                    MinecraftUiPlatform::class.java,
                ).isSynthetic,
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
                    MinecraftUiPlatform::class.java,
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
                MinecraftUiProfile::class.java,
                MinecraftUiProfileBuilder::class.java,
                MinecraftTextFieldState::class.java,
                MinecraftSlotBinding::class.java,
                MinecraftAssetId::class.java,
                MinecraftAssets::class.java,
                MinecraftImageScale::class.java,
                MinecraftSlotSource::class.java,
                MinecraftSlots::class.java,
                Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftRuntimeFactories"),
                Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftUiComponents"),
                Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftUiModifiers"),
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

    private enum class DslMethodName(
        val jvmName: String,
    ) {
        MenuBackground("menuBackground"),
        ImageBackground("imageBackground"),
        Image("Image"),
        PlayerHead("PlayerHead"),
        Text("Text"),
        ContainerBackground("containerBackground"),
        Slot("Slot"),
        TextField("TextField"),
        Button("Button"),
        Scroll("Scroll"),
    }
}
