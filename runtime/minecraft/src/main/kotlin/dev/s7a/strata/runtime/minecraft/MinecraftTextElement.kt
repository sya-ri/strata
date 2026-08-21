package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Internal immutable description for one profile-backed single-line Minecraft text run.
 *
 * @param run validated literal and immutable glyph layers retained by this description.
 * @param modifier active behavior applied to the component.
 * @param key optional stable identity among direct siblings.
 */
private class MinecraftTextElement private constructor(
    private val run: MinecraftTextRun,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        modifier = modifier,
    ) {
    /**
     * Retained text node that measures, paints, and emits unresolved semantics from one run.
     */
    private class Node(
        var run: MinecraftTextRun,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        SemanticsNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.isSatisfiedBy(run.size)) {
                "Minecraft text constraints must contain the natural text size."
            }
            return run.size
        }

        override fun paint(scope: PaintScope) {
            run.paint(scope, originX = 0, originY = 0)
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(Semantics(label = run.text, role = SemanticsRole.Text))
        }
    }

    /**
     * Owns the private element type token and constructor-only factory.
     */
    companion object {
        private val TYPE: ElementType<MinecraftTextElement, Node> =
            ElementType(
                elementClass = MinecraftTextElement::class,
                nodeClass = Node::class,
                validateLocal = { _ -> },
                createNode = { element -> Node(element.run) },
                updateNode = { previous, current, node ->
                    if (previous.run.equivalentTo(current.run)) {
                        DirtyMask.None
                    } else {
                        node.run = current.run
                        DirtyMask.of(DirtyPhase.Measure)
                    }
                },
            )

        /**
         * Creates one immutable text description without exposing its constructor to Java.
         *
         * @param run validated literal and selected immutable glyph layers.
         * @param modifier active component behavior.
         * @param key optional stable sibling identity.
         * @return a profile-backed single-line text element.
         */
        @JvmSynthetic
        internal fun create(
            run: MinecraftTextRun,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftTextElement(run, modifier, key)
    }
}

/**
 * Creates one internal text description through the private retained implementation.
 *
 * @param run validated literal and selected immutable glyph layers.
 * @param modifier active component behavior.
 * @param key optional stable sibling identity.
 * @return a profile-backed single-line text element.
 */
@JvmSynthetic
internal fun createMinecraftTextElement(
    run: MinecraftTextRun,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element = MinecraftTextElement.create(run, modifier, key)
