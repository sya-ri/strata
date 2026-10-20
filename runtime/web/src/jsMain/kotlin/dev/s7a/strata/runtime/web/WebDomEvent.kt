package dev.s7a.strata.runtime.web

/**
 * Native DOM event names decoded once at the browser boundary.
 */
internal enum class WebDomEvent(
    val nativeName: String,
) {
    PointerDown("pointerdown"),
    PointerMove("pointermove"),
    PointerUp("pointerup"),
    PointerCancel("pointercancel"),
    LostCapture("lostpointercapture"),
    Wheel("wheel"),
    KeyDown("keydown"),
    KeyUp("keyup"),
    Input("input"),
    BeforeInput("beforeinput"),
    CompositionStart("compositionstart"),
    CompositionUpdate("compositionupdate"),
    CompositionEnd("compositionend"),
    FocusIn("focusin"),
    FocusOut("focusout"),
    Scroll("scroll"),
    ;

    /**
     * Owns native event decoding at the adapter boundary.
     */
    companion object {
        /**
         * Converts an external event name before runtime gesture or editing decisions.
         */
        fun decode(name: String): WebDomEvent = checkNotNull(entries.firstOrNull { it.nativeName == name })
    }
}
