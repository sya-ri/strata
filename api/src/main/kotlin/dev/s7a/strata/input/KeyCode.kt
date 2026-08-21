package dev.s7a.strata.input

/**
 * Typed platform-neutral carrier for one physical keyboard key code.
 *
 * Platform adapters preserve the source key code without interpreting keyboard layout; character input arrives separately through [TextInputEvent.Character].
 *
 * @property value platform key identity at least as large as the unknown sentinel, with [Unknown] used when the platform cannot identify it.
 */
public data class KeyCode(
    public val value: Int,
) {
    init {
        require(UnknownValue <= value) { "A key code cannot be less than the unknown sentinel." }
    }

    /**
     * Common cross-platform key identities used by retained focus and editing behavior.
     */
    public companion object {
        private val UnknownValue: Int = -1

        /**
         * Unidentified key.
         */
        public val Unknown: KeyCode = KeyCode(UnknownValue)

        /**
         * Space key.
         */
        public val Space: KeyCode = KeyCode(32)

        /**
         * Escape key.
         */
        public val Escape: KeyCode = KeyCode(256)

        /**
         * Enter or Return key.
         */
        public val Enter: KeyCode = KeyCode(257)

        /**
         * Tab key.
         */
        public val Tab: KeyCode = KeyCode(258)

        /**
         * Backspace key.
         */
        public val Backspace: KeyCode = KeyCode(259)

        /**
         * Insert key.
         */
        public val Insert: KeyCode = KeyCode(260)

        /**
         * Delete key.
         */
        public val Delete: KeyCode = KeyCode(261)

        /**
         * Right-arrow key.
         */
        public val Right: KeyCode = KeyCode(262)

        /**
         * Left-arrow key.
         */
        public val Left: KeyCode = KeyCode(263)

        /**
         * Down-arrow key.
         */
        public val Down: KeyCode = KeyCode(264)

        /**
         * Up-arrow key.
         */
        public val Up: KeyCode = KeyCode(265)

        /**
         * Page-up key.
         */
        public val PageUp: KeyCode = KeyCode(266)

        /**
         * Page-down key.
         */
        public val PageDown: KeyCode = KeyCode(267)

        /**
         * Home key.
         */
        public val Home: KeyCode = KeyCode(268)

        /**
         * End key.
         */
        public val End: KeyCode = KeyCode(269)
    }
}
