package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.text.UiText

/**
 * Reusable declarative definition for one Minecraft screen.
 *
 * The definition owns its immutable title and pause policy and retains its private content evaluator for the definition's lifetime.
 * Host creation copies the evaluator reference without directly retaining this definition or its metadata, and each host owns an independent core session that releases its evaluator reference on terminal failure or close.
 * The same definition may create hosts on different owner threads; synchronization, lifetime, and any definition reference captured by the shared evaluator remain caller-owned.
 * Construction and property reads do not evaluate content and are safe from any thread when the contained public value contracts are honored.
 * Definitions have referential identity and do not define value equality.
 */
public sealed interface MinecraftScreenDefinition {
    /**
     * The exact unresolved screen title retained at definition construction for the platform boundary.
     */
    public val title: UiText

    /**
     * Whether the screen pauses the game while it is active.
     */
    public val pausesGame: Boolean
}
