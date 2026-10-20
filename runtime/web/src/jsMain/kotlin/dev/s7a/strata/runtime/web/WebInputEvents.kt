@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.Node
import org.w3c.dom.events.CompositionEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import kotlin.math.floor
import org.w3c.dom.events.KeyboardEvent as DomKey

/**
 * Owns delegated native input listeners; every state write runs through the host transaction.
 */
@Suppress("TooManyFunctions") // One owner installs and releases the native listener set.
internal class WebInputEvents(
    private val root: HTMLElement,
    private val session: RuntimeUiSession,
    private var runtime: WebComponentRuntime?,
    private val transaction: (() -> Unit) -> Unit,
) : AutoCloseable {
    private val listeners = LinkedHashMap<WebDomEvent, EventListener>()
    private val originalTabIndex = root.getAttribute("tabindex")
    private val originalTouchAction = root.style.getPropertyValue("touch-action")
    private var pressed: PointerButton? = null
    private var previous: IntOffset? = null
    private val blur = EventListener { transaction { reset() } }
    private val windowFocus =
        EventListener {
            transaction {
                val target = root.ownerDocument?.activeElement as? HTMLElement
                if (target?.hasAttribute("data-strata-editor") == true) focusEditor(target)
            }
        }
    private val touch =
        WebTouchGestures(root, session) { position ->
            root.focus()
            session.dispatchPointer(PointerEvent.Press(position, PointerButton.Primary))
            session.dispatchPointer(PointerEvent.Release(position, PointerButton.Primary))
        }

    /**
     * Installs only after initial markup has been validated and painted.
     */
    fun start() {
        root.tabIndex = -1
        root.style.setProperty("touch-action", "none")
        listen(WebDomEvent.PointerDown, ::pointer)
        listen(WebDomEvent.PointerMove, ::pointer)
        listen(WebDomEvent.PointerUp, ::pointer)
        listen(WebDomEvent.PointerCancel, { reset() })
        listen(WebDomEvent.LostCapture, { if (pressed != null) reset() })
        listen(WebDomEvent.Wheel, ::wheel)
        listen(WebDomEvent.KeyDown, ::keyboard)
        listen(WebDomEvent.KeyUp, ::keyboard)
        listen(WebDomEvent.Input, ::input)
        listen(WebDomEvent.BeforeInput, ::beforeInput)
        listen(WebDomEvent.CompositionStart, ::composition)
        listen(WebDomEvent.CompositionUpdate, ::composition)
        listen(WebDomEvent.CompositionEnd, ::composition)
        listen(WebDomEvent.FocusIn, ::focus)
        listen(WebDomEvent.FocusOut, { event ->
            val related = event.asDynamic().relatedTarget as? Node
            if (related == null || root.contains(related).not()) reset()
        })
        listen(WebDomEvent.Scroll, ::scroll, capture = true)
        window.addEventListener("blur", blur)
        window.addEventListener("focus", windowFocus)
    }

    override fun close() {
        listeners.forEach { (type, listener) -> root.removeEventListener(type.nativeName, listener, type == WebDomEvent.Scroll) }
        listeners.clear()
        window.removeEventListener("blur", blur)
        window.removeEventListener("focus", windowFocus)
        if (originalTabIndex == null) root.removeAttribute("tabindex") else root.setAttribute("tabindex", originalTabIndex)
        root.style.setProperty("touch-action", originalTouchAction)
        pressed = null
        previous = null
        runtime = null
        touch.close()
    }

    /**
     * Captures native editor geometry after layout has completed, outside retained frame phases.
     */
    fun synchronizeEditors() {
        val elements = root.querySelectorAll("textarea[data-strata-editor]")
        for (index in 0 until elements.length) {
            val area = elements.item(index) as? HTMLTextAreaElement ?: continue
            if (area.isConnected) node(area)?.let { node -> session.dispatchAction { node.scroll(area.scrollTop, area.clientHeight, area.scrollHeight) } }
        }
    }

    private fun listen(
        type: WebDomEvent,
        action: (Event) -> Unit,
        capture: Boolean = false,
    ) {
        val listener = EventListener { event -> transaction { action(event) } }
        listeners[type] = listener
        root.addEventListener(type.nativeName, listener, capture)
    }

    private fun position(event: MouseEvent): IntOffset {
        val bounds = root.getBoundingClientRect()
        val scaleX = if (bounds.width == 0.0) 1.0 else root.clientWidth / bounds.width
        val scaleY = if (bounds.height == 0.0) 1.0 else root.clientHeight / bounds.height
        return IntOffset(floor((event.clientX - bounds.left) * scaleX).toInt(), floor((event.clientY - bounds.top) * scaleY).toInt())
    }

    private fun pointer(event: Event) {
        val mouse = event as? MouseEvent ?: return
        val at = position(mouse)
        if (editor(event) == null && touch.handle(event, at)) return
        val button =
            when (val code = mouse.button.toInt()) {
                0 -> PointerButton.Primary
                1 -> PointerButton.Middle
                2 -> PointerButton.Secondary
                else -> PointerButton.Auxiliary(maxOf(0, code))
            }
        val translated =
            when (WebDomEvent.decode(event.type)) {
                WebDomEvent.PointerDown -> {
                    pressed = button
                    previous = at
                    if (editor(event) == null) root.focus()
                    PointerEvent.Press(at, button)
                }

                WebDomEvent.PointerUp -> {
                    pressed = null
                    previous = null
                    PointerEvent.Release(at, button)
                }

                else -> {
                    val last = previous
                    previous = at
                    val held = pressed
                    if (last != null && held != null) PointerEvent.Drag(at, held, (at.x - last.x).toDouble(), (at.y - last.y).toDouble()) else PointerEvent.Move(at)
                }
            }
        deliverPointer(event, translated)
    }

    private fun deliverPointer(
        event: Event,
        translated: PointerEvent,
    ) {
        val result = session.dispatchPointer(translated)
        if (result != InputResult.Consumed || editor(event) != null) return
        event.preventDefault()
        if (WebDomEvent.decode(event.type) == WebDomEvent.PointerDown) {
            val pointerId = event.asDynamic().pointerId
            if (pointerId != null) root.asDynamic().setPointerCapture(pointerId)
        }
    }

    private fun wheel(event: Event) {
        val wheel = event as? WheelEvent ?: return
        if (editor(event) != null) return
        val unit =
            when (wheel.deltaMode) {
                1 -> 1.0
                2 -> root.clientHeight / 9.0
                else -> 1.0 / 9.0
            }
        if (session.dispatchPointer(PointerEvent.Scroll(position(wheel), wheel.deltaX * unit, wheel.deltaY * unit)) == InputResult.Consumed) event.preventDefault()
    }

    private fun keyboard(event: Event) {
        val key = event as? DomKey ?: return
        if (key.isComposing || editor(event)?.hasAttribute("data-strata-composing") == true) return
        val code = WebKeyCodes.decode(key.code)
        val modifiers = KeyboardModifiers(key.shiftKey, key.ctrlKey, key.altKey, key.metaKey)
        val translated = if (WebDomEvent.decode(event.type) == WebDomEvent.KeyDown) KeyboardEvent.Press(code, 0, modifiers) else KeyboardEvent.Release(code, 0, modifiers)
        if (session.dispatchKeyboard(translated) == InputResult.Consumed) event.preventDefault()
    }

    private fun focus(event: Event) {
        val target = editor(event) ?: return
        focusEditor(target)
    }

    private fun focusEditor(target: HTMLElement) {
        if (node(target)?.isFocused == true) return
        val bounds = target.getBoundingClientRect()
        val outer = root.getBoundingClientRect()
        val at = IntOffset((bounds.left - outer.left + bounds.width / 2).toInt(), (bounds.top - outer.top + bounds.height / 2).toInt())
        session.focusAt(at)
    }

    private fun beforeInput(event: Event) {
        val target = editor(event) ?: return
        target.removeAttribute("data-strata-beforeinput")
        if (target.hasAttribute("data-strata-composing")) return
        val text = event.asDynamic().data as? String ?: return
        if (committedWebText("", text).any { session.dispatchTextInput(it) == InputResult.Consumed }) {
            event.preventDefault()
        } else {
            target.setAttribute("data-strata-beforeinput", "")
        }
    }

    private fun input(event: Event) {
        val target = editor(event) ?: return
        if (target.hasAttribute("data-strata-composing")) return
        val node = node(target) ?: return
        val before = node.state.value
        val value =
            when (target) {
                is HTMLInputElement -> target.value
                is HTMLTextAreaElement -> target.value
                else -> return
            }
        session.dispatchAction { node.state.commit(value) }
        val after = node.state.value
        if (target.hasAttribute("data-strata-beforeinput").not()) committedWebText(before, after).forEach(session::dispatchTextInput)
        target.removeAttribute("data-strata-beforeinput")
        scroll(event)
    }

    private fun composition(event: Event) {
        val target = editor(event) ?: return
        if (WebDomEvent.decode(event.type) == WebDomEvent.CompositionEnd) {
            target.removeAttribute("data-strata-composing")
            input(event)
            session.dispatchTextInput(TextInputEvent.Preedit("", 0, emptyList(), -1))
        } else {
            target.setAttribute("data-strata-composing", "")
            val text = (event as? CompositionEvent)?.data.orEmpty()
            session.dispatchTextInput(TextInputEvent.Preedit(text, text.length, if (text.isEmpty()) emptyList() else listOf(text), if (text.isEmpty()) -1 else 0))
        }
    }

    private fun scroll(event: Event) {
        val target = editor(event) as? HTMLTextAreaElement ?: return
        val node = node(target) ?: return
        session.dispatchAction { node.scroll(target.scrollTop, target.clientHeight, target.scrollHeight) }
    }

    private fun editor(event: Event): HTMLElement? = (event.target as? HTMLElement)?.takeIf { it.hasAttribute("data-strata-editor") }

    private fun node(element: HTMLElement): WebEditorElement.Node? = element.getAttribute("data-strata-editor")?.toIntOrNull()?.let { runtime?.editors?.get(it) }

    private fun reset() {
        pressed = null
        previous = null
        touch.close()
        session.resetInputState()
    }
}
