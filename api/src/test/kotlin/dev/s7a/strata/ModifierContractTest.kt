package dev.s7a.strata

import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies the immutable modifier value and typed node bridge contracts.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ModifierContractTest {
    @Test
    fun compositionIsOrderedImmutableAndValueBased() {
        val first = TestModifierElement(1)
        val second = TestModifierElement(2)
        val chain = Modifier.Empty.then(first).then(Modifier.Empty.then(second))
        val equal = Modifier.Empty.then(TestModifierElement(1)).then(TestModifierElement(2))

        assertEquals(listOf(first, second), chain.elements())
        assertEquals(chain, equal)
        assertEquals(chain.hashCode(), equal.hashCode())
        assertEquals(chain.toString(), equal.toString())
        assertEquals(Modifier.Empty, Modifier.Empty.then(Modifier.Empty))
        assertSame(chain, Modifier.Empty.then(chain))
        assertThrows(UnsupportedOperationException::class.java) {
            (chain.elements() as MutableList<ModifierElement>).add(TestModifierElement(3))
        }
        val repeated = Modifier.Empty.then(TestModifierElement(4)).then(TestModifierElement(5))
        assertEquals(listOf(TestModifierElement(4), TestModifierElement(5)), repeated.elements())
        assertEquals(repeated, Modifier.Empty.then(TestModifierElement(4)).then(TestModifierElement(5)))
    }

    @Test
    fun modifierNodeTypeTokensAreReferentialAndTypedBridgeUpdatesDeclaredNode() {
        val element = TestModifierElement(1)
        val previous = TestModifierElement(1)
        val current = TestModifierElement(2)
        val node = TestModifierElement.TYPE.createErased(previous)
        val retained = assertInstanceOf(TestModifierNode::class.java, node)
        assertSame(TestModifierElement.TYPE, element.type)
        assertNotSame(TestModifierElement.TYPE, TestModifierElement.anotherType())
        TestModifierElement.TYPE.validateErased(element)
        assertEquals(DirtyMask.of(DirtyPhase.Paint), TestModifierElement.TYPE.updateErased(previous, current, node))
        assertEquals(current.value, retained.value)
    }

    @Test
    fun modifierNodeTypeRejectsWrongTokensDescriptionsAndRetainedNodes() {
        val valid = TestModifierElement(1)
        val node = TestModifierElement.TYPE.createErased(valid)
        assertThrows(IllegalArgumentException::class.java) {
            TestModifierElement.TYPE.updateErased(OtherModifierElement(), valid, node)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TestModifierElement.TYPE.updateErased(valid, OtherModifierElement(), node)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TestModifierElement.TYPE.updateErased(ForgedModifierElement(), valid, node)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TestModifierElement.TYPE.updateErased(valid, ForgedModifierElement(), node)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TestModifierElement.TYPE.updateErased(valid, valid, OtherModifierNode())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TestModifierElement.TYPE.validateErased(ForgedModifierElement())
        }
    }

    @Test
    fun modifierNodeTypeRejectsWrongReturnedNodeClass() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                WrongReturnedModifierElement.TYPE.createErased(WrongReturnedModifierElement())
            }
        assertEquals("Modifier node type created the wrong node type.", thrown.message)
    }

    @Test
    fun modifierValidationPropagatesExactFailureIdentity() {
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                TestModifierElement.TYPE.validateErased(TestModifierElement(-1))
            }
        assertSame(TestModifierElement.validationFailure, thrown)
    }

    @Test
    fun typedBridgeRejectsWrongTokenAtValidationAndCreation() {
        assertThrows(IllegalArgumentException::class.java) {
            OtherModifierElement.TYPE.validateErased(TestModifierElement(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OtherModifierElement.TYPE.createErased(TestModifierElement(1))
        }
    }

    private data class TestModifierElement(
        val value: Int,
    ) : ModifierElement {
        override val type: ModifierNodeType<TestModifierElement, TestModifierNode>
            get() = TYPE

        companion object {
            val validationFailure: IllegalStateException = IllegalStateException("invalid modifier")

            val TYPE: ModifierNodeType<TestModifierElement, TestModifierNode> =
                ModifierNodeType(
                    elementClass = TestModifierElement::class,
                    nodeClass = TestModifierNode::class,
                    validateLocal = { element ->
                        if (element.value < 0) {
                            throw validationFailure
                        }
                    },
                    createNode = { element -> TestModifierNode(element.value) },
                    updateNode = { _, current, node ->
                        node.value = current.value
                        DirtyMask.of(DirtyPhase.Paint)
                    },
                )

            fun anotherType(): ModifierNodeType<TestModifierElement, TestModifierNode> =
                ModifierNodeType(
                    elementClass = TestModifierElement::class,
                    nodeClass = TestModifierNode::class,
                    validateLocal = { },
                    createNode = { TestModifierNode(0) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class OtherModifierElement : ModifierElement {
        override val type: ModifierNodeType<OtherModifierElement, TestModifierNode>
            get() = TYPE

        companion object {
            val TYPE: ModifierNodeType<OtherModifierElement, TestModifierNode> =
                ModifierNodeType(
                    elementClass = OtherModifierElement::class,
                    nodeClass = TestModifierNode::class,
                    validateLocal = { },
                    createNode = { TestModifierNode(0) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class ForgedModifierElement : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TestModifierElement.TYPE
    }

    private class WrongReturnedModifierElement : ModifierElement {
        override val type: ModifierNodeType<WrongReturnedModifierElement, ModifierNode>
            get() = TYPE

        companion object {
            val TYPE: ModifierNodeType<WrongReturnedModifierElement, ModifierNode> =
                ModifierNodeType(
                    elementClass = WrongReturnedModifierElement::class,
                    nodeClass = TestModifierNode::class,
                    validateLocal = { },
                    createNode = { WrongReturnedModifierNode() },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class TestModifierNode(
        var value: Int,
    ) : ModifierNode()

    private class OtherModifierNode : ModifierNode()

    private class WrongReturnedModifierNode : ModifierNode()
}
