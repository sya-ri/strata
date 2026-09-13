package dev.s7a.strata.resource

/**
 * Immutable 128-bit player profile identifier for one JavaScript application.
 */
public actual class ProfileUuid actual constructor(
    private val mostSignificantBits: Long,
    private val leastSignificantBits: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is ProfileUuid &&
            mostSignificantBits == other.mostSignificantBits && leastSignificantBits == other.leastSignificantBits

    override fun hashCode(): Int = mostSignificantBits.hashCode() xor leastSignificantBits.hashCode()

    override fun toString(): String {
        val hex =
            mostSignificantBits.toULong().toString(16).padStart(16, '0') +
                leastSignificantBits.toULong().toString(16).padStart(16, '0')
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) +
            "-" + hex.substring(16, 20) + "-" + hex.substring(20)
    }

    /**
     * Parses canonical profile identifiers without consulting browser or game services.
     */
    public companion object {
        /**
         * Returns an immutable identifier or rejects malformed hexadecimal text.
         */
        public fun fromString(value: String): ProfileUuid {
            require(value.length == 36 && listOf(8, 13, 18, 23).all { value[it] == '-' }) { "Invalid profile UUID." }
            val hex = value.filterIndexed { index, _ -> index !in listOf(8, 13, 18, 23) }
            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid profile UUID." }
            return ProfileUuid(hex.substring(0, 16).toULong(16).toLong(), hex.substring(16).toULong(16).toLong())
        }
    }
}

/**
 * Parses a canonical hexadecimal profile identifier without platform services.
 */
public actual fun parseProfileUuid(value: String): ProfileUuid = ProfileUuid.fromString(value)
