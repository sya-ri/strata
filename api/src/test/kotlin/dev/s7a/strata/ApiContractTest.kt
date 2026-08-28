package dev.s7a.strata

import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.ComponentRuntime
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.PlatformText
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.TranslationFallback
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.UiTextArgument
import dev.s7a.strata.text.withFont
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.lang.reflect.Modifier as JavaModifier

/**
 * Verifies the public value and bridge contracts without depending on the retained engine.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ApiContractTest {
    @Test
    fun geometryAndConstraintsRejectInvalidValuesAndUseHalfOpenBounds() {
        assertEquals(Constraints(2, 8, 3, 9), Constraints(minWidth = 2, maxWidth = 8, minHeight = 3, maxHeight = 9))
        assertEquals(Constraints.fixed(4, 5), Constraints(4, 4, 5, 5))
        assertThrows(IllegalArgumentException::class.java) { IntSize(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { IntSize(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { Constraints(minWidth = 4, maxWidth = 3) }
        assertThrows(IllegalArgumentException::class.java) { Constraints(minWidth = -1) }
        assertThrows(IllegalArgumentException::class.java) { Constraints(maxWidth = -1) }
        assertThrows(IllegalArgumentException::class.java) { Constraints(minHeight = -1) }
        assertThrows(IllegalArgumentException::class.java) { Constraints(maxHeight = -1) }
        assertThrows(IllegalArgumentException::class.java) { Constraints(minHeight = 4, maxHeight = 3) }
        assertThrows(ArithmeticException::class.java) { IntRect(Int.MIN_VALUE, 0, Int.MAX_VALUE, 0) }
        assertThrows(IllegalArgumentException::class.java) { IntRect(0, 3, 1, 2) }
        assertTrue(IntOffset(1, 1) in IntRect(1, 1, 3, 3))
        assertFalse(IntOffset(3, 1) in IntRect(1, 1, 3, 3))
        assertThrows(ArithmeticException::class.java) { IntOffset(Int.MAX_VALUE, 0) + IntOffset(1, 0) }
        assertThrows(ArithmeticException::class.java) { IntOffset(Int.MIN_VALUE, 0) - IntOffset(1, 0) }
    }

    @Test
    fun keysAndDirtyMasksAreTypedValues() {
        assertEquals(ElementKey("same"), ElementKey("same"))
        assertFalse(ElementKey("same") == ElementKey("other"))
        val paint = DirtyMask.of(DirtyPhase.Paint)
        assertTrue(DirtyPhase.Paint in paint)
        assertFalse(DirtyPhase.Measure in paint)
        assertTrue(DirtyPhase.Semantics in (paint + DirtyMask.of(DirtyPhase.Semantics)))
        assertEquals(DirtyMask.None, DirtyMask.All - DirtyMask.All)
    }

    @Test
    fun textSnapshotsCollectionsPreserveValueSemanticsAndFallbacks() {
        val arguments = mutableListOf<UiTextArgument>(UiTextArgument.IntValue(4), UiTextArgument.LongValue(5))
        val parts = mutableListOf<UiText>(UiText.Literal("a"), UiText.Literal("b"))
        val translated = UiText.Translated("greeting", arguments, TranslationFallback.UseKey)
        val concatenated = UiText.Concatenated(parts)
        arguments.add(UiTextArgument.BooleanValue(true))
        parts.add(UiText.Literal("c"))
        assertEquals(
            listOf(UiTextArgument.IntValue(4), UiTextArgument.LongValue(5)),
            translated.arguments,
        )
        assertEquals(listOf(UiText.Literal("a"), UiText.Literal("b")), concatenated.parts)
        assertThrows(UnsupportedOperationException::class.java) {
            (translated.arguments as MutableList<UiTextArgument>).add(UiTextArgument.BooleanValue(true))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (concatenated.parts as MutableList<UiText>).add(UiText.Literal("c"))
        }
        assertEquals(
            translated,
            UiText.Translated(
                "greeting",
                listOf(UiTextArgument.IntValue(4), UiTextArgument.LongValue(5)),
                TranslationFallback.UseKey,
            ),
        )
        val equalTranslated =
            UiText.Translated(
                "greeting",
                listOf(UiTextArgument.IntValue(4), UiTextArgument.LongValue(5)),
                TranslationFallback.UseKey,
            )
        assertEquals(translated.hashCode(), equalTranslated.hashCode())
        assertEquals(UiText.Translated("key", "fallback").fallback, TranslationFallback.Literal("fallback"))
        assertConcatenationValueSemantics()
        val typedArguments =
            listOf(
                UiTextArgument.IntValue(1),
                UiTextArgument.LongValue(2),
                UiTextArgument.FloatValue(3.0f),
                UiTextArgument.DoubleValue(4.0),
                UiTextArgument.BooleanValue(true),
                UiTextArgument.StringValue("five"),
                UiTextArgument.Text(UiText.Literal("six")),
            )
        val typedTranslation = UiText.Translated("typed", typedArguments)
        val typedWithFallback = UiText.Translated("typed-fallback", typedArguments, "fallback")
        assertEquals(typedArguments, typedWithFallback.arguments)
        assertEquals(TranslationFallback.Literal("fallback"), typedWithFallback.fallback)
        assertEquals(UiTextArgument.IntValue(1), typedTranslation.arguments[0])
        assertEquals(UiTextArgument.LongValue(2), typedTranslation.arguments[1])
        assertEquals(UiTextArgument.FloatValue(3.0f), typedTranslation.arguments[2])
        assertEquals(UiTextArgument.DoubleValue(4.0), typedTranslation.arguments[3])
        assertEquals(UiTextArgument.BooleanValue(true), typedTranslation.arguments[4])
        assertEquals(UiTextArgument.StringValue("five"), typedTranslation.arguments[5])
        assertEquals(UiTextArgument.Text(UiText.Literal("six")), typedTranslation.arguments[6])
        assertThrows(IllegalArgumentException::class.java) { UiText.Translated(" ") }
    }

    /**
     * Verifies multipart unresolved text equality, hashing, composition, and validation.
     */
    private fun assertConcatenationValueSemantics() {
        assertEquals(UiText.Literal(""), UiText.concat())
        assertEquals(UiText.Literal("one"), UiText.concat(UiText.Literal("one")))
        val multipart = UiText.Concatenated(listOf(UiText.Literal("a"), UiText.Literal("b")))
        val equalMultipart = UiText.Concatenated(listOf(UiText.Literal("a"), UiText.Literal("b")))
        assertEquals(multipart, equalMultipart)
        assertEquals(multipart.hashCode(), equalMultipart.hashCode())
        assertEquals(multipart, UiText.concat(UiText.Literal("a"), UiText.Literal("b")))
        assertThrows(IllegalArgumentException::class.java) { UiText.Concatenated(emptyList()) }
    }

    @Test
    fun platformTextUsesImmutableValuePayloads() {
        val first = UiText.Platform(TestPlatform("payload"))
        val second = UiText.Platform(TestPlatform("payload"))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun fontSelectionRetainsUnresolvedTextAndValueIdentity() {
        val original = UiText.Translated("strata.greeting", fallback = "日本語 한국어 🙂")
        val font = ResourceId("example", "ui/body")
        val selected = original.withFont(font) as UiText.WithFont
        assertSame(original, selected.text)
        assertEquals(font, selected.font)
        assertEquals(UiText.WithFont(original, font), selected)
        assertEquals(UiText.WithFont(original, font).hashCode(), selected.hashCode())
        val concatenated = UiText.concat(selected, UiText.Literal("suffix")) as UiText.Concatenated
        assertSame(selected, concatenated.parts[0])
    }

    @Test
    fun olderRuntimeImplementationsInheritAnExplicitUnsupportedFontDefault() {
        val fontMethod =
            ComponentRuntime::class.java.getDeclaredMethod(
                "textField",
                TextFieldState::class.java,
                IntSize::class.java,
                Boolean::class.java,
                TextStyle::class.java,
                ResourceId::class.java,
                Modifier::class.java,
                ElementKey::class.java,
            )
        assertTrue(fontMethod.isDefault)
        val runtime =
            Proxy.newProxyInstance(
                ComponentRuntime::class.java.classLoader,
                arrayOf(ComponentRuntime::class.java),
            ) { proxy, method, arguments ->
                assertEquals(fontMethod, method)
                InvocationHandler.invokeDefault(proxy, method, *arguments.orEmpty())
            } as ComponentRuntime
        val failure =
            assertThrows(UnsupportedOperationException::class.java) {
                runtime.textField(TextFieldState(""), IntSize(200, 20), true, TextStyle.TextField, ResourceId("example", "body"), Modifier.Empty, null)
            }
        assertEquals("This runtime does not support explicit font selection.", failure.message)
    }

    @Test
    fun explicitTextLayoutPreservesTheLegacyJvmDefaultAndRejectsUnsupportedCapabilities() {
        val element = ContractElement()
        val legacyMethod = ComponentRuntime::class.java.getDeclaredMethod("text", UiText::class.java, TextStyle::class.java, Modifier::class.java, ElementKey::class.java)
        var legacyCalls = 0
        val runtime =
            Proxy.newProxyInstance(ComponentRuntime::class.java.classLoader, arrayOf(ComponentRuntime::class.java)) { proxy, method, arguments ->
                if (method.isDefault) {
                    InvocationHandler.invokeDefault(proxy, method, *arguments.orEmpty())
                } else {
                    assertEquals(legacyMethod, method)
                    legacyCalls += 1
                    element
                }
            } as ComponentRuntime
        val layoutMethod = ComponentRuntime::class.java.methods.single { TextLayout::class.java in it.parameterTypes && JavaModifier.isStatic(it.modifiers).not() }
        assertTrue(layoutMethod.isDefault)
        ComponentRuntime::class.java.methods
            .filter { it.parameterTypes.firstOrNull() == TextAreaState::class.java && JavaModifier.isStatic(it.modifiers).not() }
            .forEach { method -> assertTrue(method.isDefault) }
        val text = UiText.Literal("A")
        assertSame(element, runtime.text(text, TextLayout.SingleLine, TextStyle.Normal, Modifier.Empty, null))
        assertThrows(UnsupportedOperationException::class.java) {
            runtime.text(text, TextLayout.Multiline(), TextStyle.Normal, Modifier.Empty, null)
        }
        val state = TextAreaState()
        val viewport = TextAreaViewport.Lines(120, 3)
        assertThrows(UnsupportedOperationException::class.java) {
            runtime.textArea(state, viewport, true, TextStyle.TextField, TextWrap.Word, 0, Modifier.Empty, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            runtime.textArea(state, viewport, true, TextStyle.TextField, ResourceId("example", "body"), TextWrap.Word, 0, Modifier.Empty, null)
        }
        assertEquals(1, legacyCalls)
        state.observe { }.close()
    }

    @Test
    fun multilineLayoutAndViewportCopiesRetainTheirStructuralValidation() {
        val layout = TextLayout.Multiline(maxLines = 2, overflow = TextOverflow.Ellipsis, lineSpacing = 1)
        assertThrows(IllegalArgumentException::class.java) { layout.copy(maxLines = 0) }
        assertThrows(IllegalArgumentException::class.java) { layout.copy(maxLines = -1) }
        assertThrows(IllegalArgumentException::class.java) { layout.copy(lineSpacing = -1) }
        val lines = TextAreaViewport.Lines(120, 3)
        assertThrows(IllegalArgumentException::class.java) { lines.copy(width = 0) }
        assertThrows(IllegalArgumentException::class.java) { lines.copy(width = -1) }
        assertThrows(IllegalArgumentException::class.java) { lines.copy(lines = 0) }
        assertThrows(IllegalArgumentException::class.java) { lines.copy(lines = -1) }
        val sized = TextAreaViewport.Size(IntSize(120, 40))
        assertThrows(IllegalArgumentException::class.java) { sized.copy(size = IntSize(0, 40)) }
        assertThrows(IllegalArgumentException::class.java) { sized.copy(size = IntSize(120, 0)) }
    }

    @Test
    fun publicTextOverloadsForwardTypedLayoutAndPreserveNestedFontSelections() {
        val element = ContractElement()
        val calls = mutableListOf<List<Any?>>()
        val runtime = recordingComponentRuntime(element, calls)
        val text = UiText.Literal("日本語 한국어 🙂")
        val font = ResourceId("example", "body")
        val inner = text.withFont(ResourceId("example", "inner"))
        val layout = TextLayout.Multiline(maxLines = 2, lineSpacing = 1)
        assertSame(element, ComponentRuntimeBridge.evaluate(runtime) { Text(text) })
        assertSame(element, ComponentRuntimeBridge.evaluate(runtime) { Text(text.value, layout) })
        assertSame(element, ComponentRuntimeBridge.evaluate(runtime) { Text(text.value, layout, font) })
        assertSame(element, ComponentRuntimeBridge.evaluate(runtime) { Text(inner, layout, font) })
        assertEquals(
            listOf(
                listOf(text, TextLayout.SingleLine, TextStyle.Normal, Modifier.Empty, null),
                listOf(text, layout, TextStyle.Normal, Modifier.Empty, null),
                listOf(text.withFont(font), layout, TextStyle.Normal, Modifier.Empty, null),
                listOf(inner.withFont(font), layout, TextStyle.Normal, Modifier.Empty, null),
            ),
            calls,
        )
    }

    @Test
    fun publicTextAreaOverloadsForwardCallerOwnershipWithoutClaimingTheState() {
        val element = ContractElement()
        val calls = mutableListOf<List<Any?>>()
        val runtime = recordingComponentRuntime(element, calls)
        val state = TextAreaState("日本語\r\n한국어 🙂")
        val viewport = TextAreaViewport.Lines(120, 3)
        val font = ResourceId("example", "body")
        val key = ElementKey("editor")
        assertSame(element, ComponentRuntimeBridge.evaluate(runtime) { TextArea(state, viewport, enabled = false, wrap = TextWrap.Character, lineSpacing = 2, key = key) })
        assertSame(element, ComponentRuntimeBridge.evaluate(runtime) { TextArea(state, viewport, font, wrap = TextWrap.None, lineSpacing = 1) })
        assertEquals(
            listOf(
                listOf(state, viewport, false, TextStyle.TextField, TextWrap.Character, 2, Modifier.Empty, key),
                listOf(state, viewport, true, TextStyle.TextField, font, TextWrap.None, 1, Modifier.Empty, null),
            ),
            calls,
        )
        assertThrows(IllegalArgumentException::class.java) { ComponentRuntimeBridge.evaluate(runtime) { TextArea(state, viewport, lineSpacing = -1) } }
        assertThrows(IllegalArgumentException::class.java) { ComponentRuntimeBridge.evaluate(runtime) { TextArea(state, viewport, font, lineSpacing = -1) } }
        assertEquals(2, calls.size)
        assertEquals("日本語\n한국어 🙂", state.value)
        state.observe { }.close()
    }

    @Test
    fun existingFocusTargetsInheritTheJvmDefaultWithoutEnablingNativeTextInput() {
        val capability = FocusTargetNode::class.java.getMethod("getRequiresTextInput")
        assertTrue(capability.isDefault)
        val target =
            Proxy.newProxyInstance(
                FocusTargetNode::class.java.classLoader,
                arrayOf(FocusTargetNode::class.java),
            ) { proxy, method, arguments ->
                assertEquals(capability, method)
                InvocationHandler.invokeDefault(proxy, method, *arguments.orEmpty())
            } as FocusTargetNode
        assertFalse(target.requiresTextInput)
    }

    @Test
    fun elementChildrenSnapshotCallerMutation() {
        val children = mutableListOf<Element>(ContractElement(), ContractElement())
        val element = ContractElement(children = children)
        children.clear()
        assertEquals(2, element.children.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (element.children as MutableList<Element>).add(ContractElement())
        }
    }

    @Test
    fun pointerVariantsRejectNonFiniteScrollAndPreserveTypedButtons() {
        assertEquals(PointerButton.Auxiliary(0), PointerButton.Auxiliary(0))
        assertThrows(IllegalArgumentException::class.java) { PointerButton.Auxiliary(-1) }
        assertEquals(PointerButton.Primary, PointerEvent.Press(IntOffset.Zero, PointerButton.Primary).button)
        assertEquals(PointerButton.Secondary, PointerEvent.Press(IntOffset.Zero, PointerButton.Secondary).button)
        assertEquals(PointerButton.Middle, PointerEvent.Release(IntOffset.Zero, PointerButton.Middle).button)
        assertEquals(PointerButton.Primary, PointerEvent.Drag(IntOffset.Zero, PointerButton.Primary, 1.5, -2.0).button)
        assertThrows(IllegalArgumentException::class.java) {
            PointerEvent.Drag(IntOffset.Zero, PointerButton.Primary, Double.NaN, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PointerEvent.Drag(IntOffset.Zero, PointerButton.Primary, 0.0, Double.NEGATIVE_INFINITY)
        }
        assertEquals(2.5, PointerEvent.Scroll(IntOffset.Zero, 2.5, -1.0).deltaX)
        assertThrows(IllegalArgumentException::class.java) { PointerEvent.Scroll(IntOffset.Zero, Double.NaN, 0.0) }
        assertThrows(IllegalArgumentException::class.java) {
            PointerEvent.Scroll(IntOffset.Zero, 0.0, Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun rolesAreExternallyImplementableAndBuiltInsRemainStable() {
        val custom = ApplicationRole
        val role: SemanticsRole = custom
        assertEquals(custom, role)
        assertEquals(SemanticsRole.Button, SemanticsRole.Button)
        assertEquals(SemanticsRole.Text, SemanticsRole.Text)
        assertNotEquals(SemanticsRole.TextField, SemanticsRole.TextArea)
    }

    @Test
    fun nodeInvalidationRequiresOneRuntimeBinding() {
        val node = ContractNode()
        assertThrows(IllegalStateException::class.java) { node.invalidatePaint() }
        val received = mutableListOf<DirtyMask>()
        val release = node.bindRuntime { mask -> received += mask }
        assertThrows(IllegalStateException::class.java) { node.bindRuntime { } }
        node.invalidatePaint()
        assertEquals(listOf(DirtyMask.of(DirtyPhase.Paint)), received)
        release()
        assertThrows(IllegalStateException::class.java) { node.invalidatePaint() }
        assertThrows(IllegalStateException::class.java) { node.bindRuntime { } }
    }

    @Test
    fun elementTypeBridgeRejectsAlternativeTokensWrongElementsAndWrongNodes() {
        val element = ContractElement()
        val alternative = ContractElement.ALTERNATIVE
        assertThrows(IllegalArgumentException::class.java) { alternative.validateErased(element) }
        assertThrows(IllegalArgumentException::class.java) { alternative.createErased(element) }
        assertThrows(IllegalArgumentException::class.java) {
            alternative.updateErased(element, element, ContractNode())
        }
        val alternativeCurrent = ContractElement(type = alternative)
        assertThrows(IllegalArgumentException::class.java) {
            ContractElement.TYPE.updateErased(element, alternativeCurrent, ContractNode())
        }
        val wrongElement = OtherElement()
        assertThrows(IllegalArgumentException::class.java) { ContractElement.TYPE.validateErased(wrongElement) }
        assertThrows(IllegalArgumentException::class.java) { ContractElement.TYPE.createErased(wrongElement) }
        assertThrows(IllegalArgumentException::class.java) {
            ContractElement.TYPE.updateErased(wrongElement, element, ContractNode())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContractElement.TYPE.updateErased(element, wrongElement, ContractNode())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContractElement.TYPE.updateErased(element, element, OtherNode())
        }
        val wrongReturnElement = ContractElement(type = ContractElement.WRONG_RETURN)
        assertThrows(IllegalArgumentException::class.java) {
            ContractElement.WRONG_RETURN.createErased(wrongReturnElement)
        }
    }

    private data class TestPlatform(
        val value: String,
    ) : PlatformText

    /**
     * Records synchronous public-DSL forwarding without resolving resources or attaching a retained node.
     */
    private fun recordingComponentRuntime(
        element: Element,
        calls: MutableList<List<Any?>>,
    ): ComponentRuntime =
        Proxy.newProxyInstance(ComponentRuntime::class.java.classLoader, arrayOf(ComponentRuntime::class.java)) { _, _, arguments ->
            calls.add(arguments.orEmpty().toList())
            element
        } as ComponentRuntime

    private data object ApplicationRole : SemanticsRole

    private class ContractNode : Node() {
        /**
         * Exercises protected node-local invalidation for the binding contract.
         */
        fun invalidatePaint() {
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }
    }

    private class OtherNode : Node()

    private class ContractElement(
        identity: ElementIdentity = ElementIdentity.Positional,
        type: ElementType<*, *> = TYPE,
        children: List<Element> = emptyList(),
    ) : Element(identity, type, children) {
        /**
         * Stable token and negative-test variants for the element bridge.
         */
        public companion object {
            val TYPE: ElementType<ContractElement, ContractNode> =
                ElementType(
                    elementClass = ContractElement::class,
                    nodeClass = ContractNode::class,
                    validateLocal = { },
                    createNode = { ContractNode() },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
            val ALTERNATIVE: ElementType<ContractElement, ContractNode> =
                ElementType(
                    elementClass = ContractElement::class,
                    nodeClass = ContractNode::class,
                    validateLocal = { },
                    createNode = { ContractNode() },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
            val WRONG_RETURN: ElementType<ContractElement, Node> =
                ElementType(
                    elementClass = ContractElement::class,
                    nodeClass = ContractNode::class,
                    validateLocal = { },
                    createNode = { OtherNode() },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class OtherElement : Element(ElementIdentity.Positional, ContractElement.TYPE)
}
