@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Proves that newly accelerated effects cannot split overlapping CPU composition into separately rounded layers.
 * Opaque fills are destination backgrounds or replacements; other exact opaque masks must be disjoint.
 * Any potentially translucent primitive retains CPU composition, even outside the admitted image's bounds.
 * Inserting a native barrier can split and change the rounding of those other commands' portable run.
 * Classification starts only when an extended effect needs proof and visits the current list at most once.
 * One of at most 8,192 work units covers candidate validation, one command classification and amortized bounds append,
 * or one mask occurrence visit with its complete constant-size half-open overlap check.
 * Every classification and geometry loop spends that shared budget; rejection after exhaustion is constant work.
 * The synchronous render-thread operation retains only bounded occurrence indexes and immutable bounds, with no source pixels or native handles.
 * Exhaustion selects the existing exact CPU path; partial classification fails closed, and no proof state survives preparation.
 */
internal class FabricMinecraftSamplingComposition(
    private val commands: List<DrawCommand>,
) {
    private var remaining = 8_192
    private var state = State.Pending
    private var masks: List<Mask> = emptyList()

    /**
     * Number of candidate validations charged to this preparation's shared work bound.
     */
    @get:JvmSynthetic
    internal var admissionVisits: Int = 0
        private set

    /**
     * Current-list classification visits, including a blocking command or failed read.
     */
    @get:JvmSynthetic
    internal var classificationVisits: Int = 0
        private set

    /**
     * Number of cached occurrence visits, including the candidate's own occurrence.
     */
    @get:JvmSynthetic
    internal var maskVisits: Int = 0
        private set

    /**
     * Complete half-open geometry checks, each contained in one charged mask visit.
     */
    @get:JvmSynthetic
    internal var geometryChecks: Int = 0
        private set

    /**
     * Bounded scalar/bounds records; global rejection, exhaustion and failed classification retain none.
     */
    @get:JvmSynthetic
    internal val retainedOccurrenceCount: Int
        get() = masks.size

    /**
     * All charged validation, classification and overlap work; never more than 8,192 units.
     */
    @get:JvmSynthetic
    internal val workCount: Int
        get() = 8_192 - remaining

    /**
     * Checks a supported opaque-mask command at its original occurrence, without reading source pixels.
     * Equal or repeated command objects remain separate occurrences; invalid candidates cannot establish a proof.
     * Clips are ignored conservatively; commands separated by native barriers are still checked for overlap.
     */
    @JvmSynthetic
    internal fun admits(
        occurrence: Int,
        command: DrawCommand.SampledImage,
    ): Boolean {
        if (spend().not()) return reject()
        admissionVisits += 1
        if (state == State.Rejected) return false
        if ((occurrence in commands.indices).not() || commands[occurrence] !== command) return false
        if (command.alphaCutoff != 1f || command.hasExactFabricSamplingEffects().not()) return false
        if (state == State.Pending && classify().not()) return false
        for (index in masks.indices) {
            if (spend().not()) return reject()
            maskVisits += 1
            val other = masks[index]
            if (other.occurrence == occurrence) continue
            geometryChecks += 1
            if (intersects(command.destination, other.bounds)) return false
        }
        return true
    }

    private fun classify(): Boolean {
        state = State.Rejected
        val prepared = ArrayList<Mask>()
        for (index in commands.indices) {
            if (spend().not()) return false
            classificationVisits += 1
            if (appendSupported(index, commands[index], prepared).not()) return false
        }
        masks = prepared
        state = State.Ready
        return true
    }

    private fun appendSupported(
        index: Int,
        command: DrawCommand,
        prepared: MutableList<Mask>,
    ): Boolean =
        when (command) {
            is DrawCommand.FillRectangle -> command.color.value ushr 24 == 255
            is DrawCommand.SampledImage -> {
                if (command.tint.value ushr 24 == 0) {
                    true
                } else if (command.alphaCutoff != 1f || command.hasExactFabricSamplingEffects().not()) {
                    false
                } else {
                    prepared.add(Mask(index, command.destination))
                }
            }

            is DrawCommand.BlitImage, is DrawCommand.BlitImagePixels, is DrawCommand.Platform -> false
            is DrawCommand.PushClip, is DrawCommand.PushFractionalClip, DrawCommand.PopClip -> true
        }

    private fun spend(): Boolean {
        if (remaining == 0) return false
        remaining -= 1
        return true
    }

    private fun reject(): Boolean {
        state = State.Rejected
        masks = emptyList()
        return false
    }

    private fun intersects(
        target: FloatRect,
        other: FloatRect,
    ): Boolean = other.left.toDouble() < target.right.toDouble() && target.left.toDouble() < other.right.toDouble() && other.top.toDouble() < target.bottom.toDouble() && target.top.toDouble() < other.bottom.toDouble()

    private data class Mask(
        val occurrence: Int,
        val bounds: FloatRect,
    )

    private enum class State {
        Pending,
        Ready,
        Rejected,
    }
}
