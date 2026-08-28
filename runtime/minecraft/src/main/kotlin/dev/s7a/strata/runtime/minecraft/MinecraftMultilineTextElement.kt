package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.UiText
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Immutable multiline Text configuration with all measured layout and clip state owned by each retained node.
 *
 * The description is safely reusable by multiple trees borrowing the same open owner-thread renderer.
 * Natural text keeps its ink overhang; explicit viewport axes use portable clipping without modifying glyph geometry.
 */
private class MinecraftMultilineTextElement(
    private val content: MinecraftTextContent,
    private val renderer: MinecraftTextRenderer,
    private val policy: TextLayout.Multiline,
    private val style: TextStyle,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        modifier = modifier,
    ) {
    /**
     * Sole current-layout owner for one retained text node; no mutable presentation state is written into its description.
     */
    private class Node(
        var content: MinecraftTextContent?,
        var renderer: MinecraftTextRenderer?,
        var policy: TextLayout.Multiline,
        var style: TextStyle,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        SemanticsNode,
        LifecycleNode {
        var currentLayout: MinecraftTextLayout? = null
        private var clip: IntRect? = null

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            val result = MinecraftTextLineBreaker.create(checkNotNull(content), checkNotNull(renderer), policy, constraints.maxWidth, style, maxHeight = constraints.maxHeight)
            currentLayout = result
            clip = MinecraftMultilineTextViewport.clipBounds(result, constraints)
            return constraints.constrain(result.size)
        }

        override fun paint(scope: PaintScope) {
            val result = checkNotNull(currentLayout)
            val bounds = clip
            if (bounds == null) result.paint(scope) else scope.withClip(bounds) { result.paintVisible(scope, bounds) }
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(Semantics(label = checkNotNull(content).text, role = SemanticsRole.Text))
        }

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            currentLayout = null
            content = null
            renderer = null
            clip = null
        }
    }

    companion object {
        private val TYPE: ElementType<MinecraftMultilineTextElement, Node> =
            ElementType(
                elementClass = MinecraftMultilineTextElement::class,
                nodeClass = Node::class,
                validateLocal = { _ -> },
                createNode = { Node(it.content, it.renderer, it.policy, it.style) },
                updateNode = { previous, current, node ->
                    val sameContent = previous.content.equivalentTo(current.content) && previous.renderer === current.renderer
                    if (sameContent && previous.policy == current.policy && previous.style == current.style) {
                        DirtyMask.None
                    } else {
                        node.content = current.content
                        node.renderer = current.renderer
                        node.policy = current.policy
                        node.style = current.style
                        node.currentLayout = null
                        DirtyMask.of(DirtyPhase.Measure)
                    }
                },
            )

        /**
         * Validates the current logical value without constructing or retaining any node-owned presentation state.
         *
         * @param text complete unresolved semantic value.
         * @param renderer borrowed owner-thread text service.
         * @param policy structural multiline behavior.
         * @param style profile color and shadow policy.
         * @param modifier active behavior around the text.
         * @param key optional sibling identity.
         * @return immutable reusable element description without native resource ownership.
         */
        @JvmSynthetic
        internal fun create(
            text: UiText,
            renderer: MinecraftTextRenderer,
            policy: TextLayout.Multiline,
            style: TextStyle,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftMultilineTextElement(MinecraftTextContent.create(text, multiline = true), renderer, policy, style, modifier, key)
    }
}

/**
 * Creates structurally constrained multiline Text while preserving its full semantics and natural font overhang.
 *
 * @param text resolved literals and font wrappers, allowing mandatory Unicode hard breaks.
 * @param renderer borrowed host-owned text service; nodes release it before host closure.
 * @param layout immutable wrap, line-count, overflow, and spacing policy.
 * @param style profile-backed color and shadow treatment.
 * @param modifier active behavior around the logical text extent.
 * @param key optional stable sibling identity.
 * @return immutable description; each retained node owns and disposes its independent current layout.
 * @throws IllegalArgumentException for malformed Unicode, unresolved text, or legacy formatting markers.
 */
@JvmSynthetic
internal fun createMinecraftMultilineTextElement(
    text: UiText,
    renderer: MinecraftTextRenderer,
    layout: TextLayout.Multiline,
    style: TextStyle,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element = MinecraftMultilineTextElement.create(text, renderer, layout, style, modifier, key)
