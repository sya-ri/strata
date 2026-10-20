@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.ScrollStateObserver
import dev.s7a.strata.component.TextInputAppearance
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
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Native editor description; only its retained node acquires state observation.
 */
internal class WebEditorElement(
    val state: WebEditorState,
    val size: IntSize,
    val enabled: Boolean,
    val style: TextStyle,
    val appearance: TextInputAppearance,
    val font: String,
    val wrap: Boolean,
    val lineSpacing: Int,
    val runtime: WebComponentRuntime,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional, if (state is WebEditorState.Area) AREA else FIELD, modifier = modifier) {
    /**
     * Owns identity, focus and the exclusive observer until terminal release.
     */
    internal class Node(
        initial: WebEditorElement,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        FocusTargetNode,
        SemanticsNode,
        LifecycleNode {
        val identity: Int = initial.runtime.allocateIdentity()
        private var element: WebEditorElement? = initial
        private var subscription: AutoCloseable? = null
        private var scrollObserver: ScrollStateObserver? = null
        private var focused = false

        /**
         * Committed logical focus used to avoid redundant native acquisition.
         */
        val isFocused: Boolean get() = focused
        val state: WebEditorState get() = checkNotNull(element).state
        override val acceptsFocus: Boolean get() = element?.enabled == true
        override val requiresTextInput: Boolean get() = true

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(checkNotNull(element).size)

        override fun paint(scope: PaintScope) {
            val current = checkNotNull(element)
            if (scope.size.width == 0 || scope.size.height == 0) return
            scope.drawPlatform(
                WebEditorPresentation(
                    identity,
                    state is WebEditorState.Area,
                    state.value,
                    state.maxLength,
                    current.enabled,
                    focused,
                    current.style,
                    current.appearance,
                    current.font,
                    current.wrap,
                    current.lineSpacing,
                    state.scroll?.metrics?.offset ?: 0.0,
                ),
                IntRect(0, 0, scope.size.width, scope.size.height),
            )
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(Semantics(value = UiText.Literal(state.value), role = SemanticsRole.TextField, disabled = acceptsFocus.not()))
        }

        override fun onFocusChanged(focused: Boolean) {
            this.focused = focused
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }

        override fun attach() {
            checkNotNull(element).runtime.editors[identity] = this
            observe()
        }

        override fun detach() {
            checkNotNull(element).runtime.editors.remove(identity)
            subscription?.close()
            subscription = null
            scrollObserver?.close()
            scrollObserver = null
        }

        override fun dispose() {
            element = null
        }

        /**
         * Reconciles appearance and state without replacing the native editing node.
         */
        fun update(current: WebEditorElement): DirtyMask {
            val previous = checkNotNull(element)
            val sameState =
                when (val before = previous.state) {
                    is WebEditorState.Field -> (current.state as? WebEditorState.Field)?.state === before.state
                    is WebEditorState.Area -> (current.state as? WebEditorState.Area)?.state === before.state
                }
            element = current
            if (sameState.not()) {
                subscription?.close()
                scrollObserver?.close()
                observe()
            }
            return DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Paint, DirtyPhase.Semantics)
        }

        private fun observe() {
            subscription = state.observe { invalidate(DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics)) }
            scrollObserver = state.scroll?.observe { invalidate(DirtyMask.of(DirtyPhase.Paint)) }
        }

        /**
         * Publishes native scroll geometry outside declarative frame phases.
         */
        fun scroll(
            offset: Double,
            viewport: Int,
            content: Int,
        ) {
            val scroll = state.scroll ?: return
            val observer = checkNotNull(scrollObserver)
            val previous = scroll.metrics
            scroll.updateGeometry(viewport, content, observer)
            scroll.scrollTo(offset, observer)
            if (scroll.metrics != previous) invalidate(DirtyMask.of(DirtyPhase.Paint))
        }
    }

    private companion object {
        val FIELD = type()
        val AREA = type()

        fun type(): ElementType<WebEditorElement, Node> = ElementType(WebEditorElement::class, Node::class, { _ -> }, ::Node, { _, current, node -> node.update(current) })
    }
}
