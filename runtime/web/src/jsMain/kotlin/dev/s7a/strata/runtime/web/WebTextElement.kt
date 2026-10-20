@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.web

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
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.ceil
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Bounded multiline native text measured with the same prepared browser font used for paint.
 */
internal class WebTextElement(
    val text: String,
    val font: String,
    val lineHeight: Int,
    val style: TextStyle,
    val layout: TextLayout.Multiline,
    val runtime: WebComponentRuntime,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional, TYPE, modifier = modifier) {
    /**
     * Holds only this node's current line plan and presentation identity.
     */
    private class Node(
        private var element: WebTextElement,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        SemanticsNode {
        private val identity = element.runtime.allocateIdentity()
        private var visible = ""

        @Suppress("CyclomaticComplexMethod") // Scalar wrapping and truncation implement the explicit multiline policy.
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            val context = checkNotNull(canvas.getContext("2d") as? CanvasRenderingContext2D)
            context.font = element.font

            fun width(text: String): Int = ceil(context.measureText(text).width).toInt()
            val lines = ArrayList<String>()
            val normalized = element.text.replace("\r\n", "\n").replace(Regex("[\r\u000B\u000C\u0085\u2028\u2029]"), "\n")
            for (paragraph in normalized.split('\n')) {
                if (element.layout.wrap == TextWrap.None || paragraph.isEmpty()) {
                    lines.add(paragraph)
                    continue
                }
                var line = ""
                var index = 0
                while (index < paragraph.length) {
                    val length = if (paragraph[index].isHighSurrogate() && index + 1 < paragraph.length) 2 else 1
                    val scalar = paragraph.substring(index, index + length)
                    if (line.isNotEmpty() && constraints.maxWidth < width(line + scalar)) {
                        val boundary = if (element.layout.wrap == TextWrap.Word) line.indexOfLast { it == ' ' || it == '\t' } else -1
                        if (0 <= boundary) {
                            lines.add(line.substring(0, boundary))
                            line = line.substring(boundary + 1)
                        } else {
                            lines.add(line)
                            line = ""
                        }
                    }
                    line += scalar
                    index += length
                }
                lines.add(line)
            }
            val spacing = element.layout.lineSpacing
            val stride = element.lineHeight + spacing
            val count = minOf(lines.size, element.layout.maxLines, ((constraints.maxHeight.toLong() + spacing) / stride).toInt())
            val shown = lines.take(count).toMutableList()
            if (shown.isNotEmpty() && count < lines.size && element.layout.overflow == TextOverflow.Ellipsis) {
                var last = shown.last()
                while (last.isNotEmpty() && constraints.maxWidth < width("$last…")) last = last.dropLast(if (last.last().isLowSurrogate()) 2 else 1)
                shown[shown.lastIndex] = if (width("…") <= constraints.maxWidth) "$last…" else last
            }
            visible = shown.joinToString("\n")
            return constraints.constrain(IntSize(shown.maxOfOrNull(::width) ?: 0, maxOf(0, count * stride - spacing)))
        }

        override fun paint(scope: PaintScope) {
            if (scope.size.width == 0 || scope.size.height == 0) return
            scope.drawPlatform(WebPresentation(identity, WebPresentation.Kind.Text, visible, true, element.style, font = element.font, lineHeight = element.lineHeight + element.layout.lineSpacing), IntRect(0, 0, scope.size.width, scope.size.height))
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(Semantics(label = UiText.Literal(element.text), role = SemanticsRole.Text))
        }

        /**
         * Invalidates the current line plan on any reconciled presentation change.
         */
        fun update(current: WebTextElement): DirtyMask {
            element = current
            return DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Paint, DirtyPhase.Semantics)
        }
    }

    private companion object {
        val TYPE = ElementType(WebTextElement::class, Node::class, { _ -> }, ::Node, { _, current, node -> node.update(current) })
    }
}
