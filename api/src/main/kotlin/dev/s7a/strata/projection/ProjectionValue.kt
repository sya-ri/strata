package dev.s7a.strata.projection

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
     * An owned byte snapshot; construction and extraction copy the caller's array.
     */
    public class Bytes(
        value: ByteArray,
    ) : ProjectionValue {
        private val content: ByteArray = value.copyOf()

        /**
         * The owned byte count, available without allocating a copy.
         */
        public val size: Int get() = content.size

        /**
         * Returns a detached mutable copy of the owned bytes.
         */
        public fun toByteArray(): ByteArray = content.copyOf()

        override fun equals(other: Any?): Boolean = other is Bytes && content.contentEquals(other.content)

        override fun hashCode(): Int = content.contentHashCode()
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
