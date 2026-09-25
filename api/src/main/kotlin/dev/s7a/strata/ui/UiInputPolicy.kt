package dev.s7a.strata.ui

/**
 * Gameplay permissions while this UI owns input; text editing temporarily blocks every permission.
 */
public data class UiInputPolicy(
    public val movement: Boolean = false,
    public val jump: Boolean = false,
    public val sneak: Boolean = false,
    public val sprint: Boolean = false,
    public val look: Boolean = false,
    public val attack: Boolean = false,
    public val use: Boolean = false,
    public val hotbar: Boolean = false,
    public val drop: Boolean = false,
    public val swapHands: Boolean = false,
    public val pick: Boolean = false,
) {
    /**
     * Returns the permission for a decoded native game action.
     */
    public fun allows(action: UiGameAction): Boolean =
        when (action) {
            UiGameAction.Movement -> movement
            UiGameAction.Jump -> jump
            UiGameAction.Sneak -> sneak
            UiGameAction.Sprint -> sprint
            UiGameAction.Look -> look
            UiGameAction.Attack -> attack
            UiGameAction.Use -> use
            UiGameAction.Hotbar -> hotbar
            UiGameAction.Drop -> drop
            UiGameAction.SwapHands -> swapHands
            UiGameAction.Pick -> pick
        }

    /**
     * Shared immutable policies for ordinary UI, locomotion, and unrestricted gameplay.
     */
    public companion object {
        /**
         * Ordinary screen default.
         */
        public val BlockAll: UiInputPolicy = UiInputPolicy()

        /**
         * Allow locomotion without item actions or camera movement.
         */
        public val Movement: UiInputPolicy = UiInputPolicy(movement = true, jump = true, sneak = true, sprint = true)

        /**
         * Allow every gameplay action that the UI has not consumed.
         */
        public val All: UiInputPolicy =
            UiInputPolicy(
                movement = true,
                jump = true,
                sneak = true,
                sprint = true,
                look = true,
                attack = true,
                use = true,
                hotbar = true,
                drop = true,
                swapHands = true,
                pick = true,
            )
    }
}
