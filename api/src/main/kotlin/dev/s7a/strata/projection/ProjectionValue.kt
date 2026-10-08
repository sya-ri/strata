package dev.s7a.strata.projection

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Detached immutable data admitted at a declaration projection boundary.
 * Values contain no state sources, handlers, platform objects, or executable code.
 * A transport must bound encoded sizes and nesting before allocating incoming values.
 */
public sealed interface ProjectionValue {
    /**
     * An explicitly absent optional value.
     */
    public data object Absent : ProjectionValue

    /**
     * A boolean property.
     */
    public data class Flag(
        public val value: Boolean,
    ) : ProjectionValue

    /**
     * A signed integer property, narrowed with checked bounds by its schema decoder.
     */
    public data class Integer(
        public val value: Long,
    ) : ProjectionValue

    /**
     * A finite floating-point property.
     */
    public data class Real(
        public val value: Double,
    ) : ProjectionValue {
        init {
            require(value.isFinite()) { "Projected real values must be finite." }
        }
    }

    /**
     * Text whose encoded byte limit is enforced by the transport.
     */
    public data class Text(
        public val value: String,
    ) : ProjectionValue

    /**
     * An immutable byte snapshot; public construction and extraction copy the caller's array.
     * Privileged runtimes may transfer a fresh completed payload through [fromOwned].
     */
    public class Bytes private constructor(
        value: ByteArray,
        ownership: Ownership,
    ) : ProjectionValue {
        private val content: ByteArray =
            when (ownership) {
                Ownership.Copy -> value.copyOf()
                Ownership.Transfer -> value
            }

        /**
         * Snapshots the caller's mutable array without retaining its storage.
         */
        public constructor(value: ByteArray) : this(value, Ownership.Copy)

        /**
         * The owned byte count, available without allocating a copy.
         */
        public val size: Int get() = content.size

        /**
         * Returns a detached mutable copy of the owned bytes.
         */
        public fun toByteArray(): ByteArray = content.copyOf()

        /**
         * Copies the complete immutable payload into caller-owned storage without an intermediate array.
         * The caller exclusively owns the destination during this call; no array reference is retained or exposed.
         * Invalid destination bounds fail according to [ByteArray.copyInto].
         */
        @InternalStrataRuntimeApi
        public fun copyInto(
            destination: ByteArray,
            destinationOffset: Int,
        ) {
            content.copyInto(destination, destinationOffset)
        }

        override fun equals(other: Any?): Boolean = other is Bytes && content.contentEquals(other.content)

        override fun hashCode(): Int = content.contentHashCode()

        /**
         * Narrow ownership bridge for runtimes which have already validated and completed a detached payload.
         */
        @InternalStrataRuntimeApi
        public companion object {
            /**
             * Takes exclusive ownership of an exact payload array without copying it.
             * The caller must relinquish every mutable alias before publication and never mutate the array again.
             * The array must not be a shared input, output, pooled buffer, or oversized backing store.
             * Once transferred, this value supports independent concurrent readers through immutable access.
             */
            @InternalStrataRuntimeApi
            public fun fromOwned(value: ByteArray): Bytes = Bytes(value, Ownership.Transfer)
        }

        /**
         * Distinguishes defensive construction from the privileged exclusive ownership transfer.
         */
        private enum class Ownership {
            Copy,
            Transfer,
        }
    }

    /**
     * An ordered defensive snapshot of schema fields or homogeneous collection entries.
     */
    public class Sequence(
        values: List<ProjectionValue>,
    ) : ProjectionValue {
        public val values: List<ProjectionValue> = values.toList()

        override fun equals(other: Any?): Boolean = other is Sequence && values == other.values

        override fun hashCode(): Int = values.hashCode()
    }
}
