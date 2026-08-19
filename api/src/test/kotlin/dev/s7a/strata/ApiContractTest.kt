package dev.s7a.strata

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
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.Node
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.PlatformText
import dev.s7a.strata.text.TranslationFallback
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.UiTextArgument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
