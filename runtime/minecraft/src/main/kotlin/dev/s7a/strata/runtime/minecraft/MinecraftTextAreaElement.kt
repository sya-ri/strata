package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.KeyboardInputNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.node.TextInputNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Immutable multiline-editor inputs whose independent retained nodes own their own editing intervals.
 *
 * Descriptions retain no node, callback, composition, or mutable binding to a tree.
 * The caller state's single-editor rule still forbids simultaneous attachments with the same state.
 * Explicit portable clipping preserves the original glyph geometry and final-density sampling.
 */
private class MinecraftTextAreaElement(
    val configuration: MinecraftTextAreaConfiguration,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        TYPE,
        modifier = modifier,
    ) {
    private class Node(
        configuration: MinecraftTextAreaConfiguration,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        PointerInputNode,
        FocusTargetNode,
        KeyboardInputNode,
        TextInputNode,
        SemanticsNode,
        LifecycleNode {
        private var editor: MinecraftTextAreaEditor? = MinecraftTextAreaEditor(configuration, ::invalidate)

        override val acceptsFocus: Boolean
            get() = checkNotNull(editor).configuration.enabled

        override val requiresTextInput: Boolean
            get() = acceptsFocus

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            val current = checkNotNull(editor)
            require(constraints.isSatisfiedBy(current.configuration.size)) { "Minecraft TextArea constraints must contain its requested size." }
            current.measure()
            return current.configuration.size
        }

        override fun paint(scope: PaintScope) {
            val current = checkNotNull(editor)
            val settings = current.configuration
            val sprite = if (current.focused && settings.enabled) settings.highlightedSprite else settings.normalSprite
            paintMinecraftNineSlice(scope, sprite, Insets.all(1), NineSliceCenterMode.Tiled)
            scope.withClip(IntRect(4, 4, settings.size.width - 4, settings.size.height - 4)) {
                current.paint(scope)
            }
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult = checkNotNull(editor).pointer(event, localPosition)

        override fun onFocusChanged(focused: Boolean) {
            checkNotNull(editor).focus(focused)
        }

        override fun onKeyboardEvent(event: KeyboardEvent): InputResult = checkNotNull(editor).keyboard(event)

        override fun onTextInput(event: TextInputEvent): InputResult = checkNotNull(editor).textInput(event)

        override fun semantics(scope: SemanticsScope) {
            val current = checkNotNull(editor).configuration
            scope.emit(Semantics(role = SemanticsRole.TextArea, value = UiText.Literal(current.state.value), disabled = current.enabled.not()))
        }

        override fun attach() {
            checkNotNull(editor).attach()
        }

        override fun detach() {
            checkNotNull(editor).detach()
        }

        override fun dispose() {
            editor?.dispose()
            editor = null
        }

        fun updateFrom(description: MinecraftTextAreaElement): DirtyMask = checkNotNull(editor).update(description.configuration)
    }

    companion object {
        private val TYPE: ElementType<MinecraftTextAreaElement, Node> =
            ElementType(
                MinecraftTextAreaElement::class,
                Node::class,
                { },
                { Node(it.configuration) },
                { _, current, node -> node.updateFrom(current) },
            )
    }
}

/**
 * Creates a multiline editor from exact profile frame pixels and a borrowed font service.
 *
 * The resulting description does not attach observers until the retained tree attaches its node.
 * It exposes committed text as [Semantics.value] with [SemanticsRole.TextArea], but no typed accessibility edit or focus actions.
 *
 * @param normalSprite detached unfocused frame pixels.
 * @param highlightedSprite detached focused frame pixels.
 * @param renderer borrowed host-owned text service.
 * @param font structural font identifier.
 * @param state caller-owned canonical multiline text and vertical position.
 * @param viewport fixed outer size or requested visible line count.
 * @param enabled whether focus and editing are accepted.
 * @param style profile-backed text colors and shadows.
 * @param wrap presentation-only wrapping policy.
 * @param lineSpacing non-negative additional logical pixels between lines.
 * @param modifier active outer layout and input behavior.
 * @param key optional stable sibling identity.
 * @return immutable private editor description without attached observer or native resource ownership.
 */
@JvmSynthetic
internal fun createMinecraftTextAreaElement(
    normalSprite: DrawImage,
    highlightedSprite: DrawImage,
    renderer: MinecraftTextRenderer,
    font: ResourceId,
    state: TextAreaState,
    viewport: TextAreaViewport,
    enabled: Boolean,
    style: TextStyle,
    wrap: TextWrap,
    lineSpacing: Int,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element =
    MinecraftTextAreaElement(
        MinecraftTextAreaConfiguration(normalSprite, highlightedSprite, renderer, font, state, viewport, enabled, style, wrap, lineSpacing),
        modifier,
        key,
    )
