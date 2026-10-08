package dev.s7a.strata.quality.benchmark

/**
 * Typed decoding of JVM implementation method names at the untimed image observation adapter boundary.
 */
internal enum class RemoteImageObservedMethod(private val methodName: String) {
    EncodePixels("encodePixels"),
    CopyArgb("copyArgb"),
    ;

    /**
     * Unknown JVM methods carry no counted image-work meaning.
     */
    companion object {
        fun decode(methodName: String): RemoteImageObservedMethod? = entries.find { it.methodName.contentEquals(methodName) }
    }
}
