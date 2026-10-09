package dev.s7a.strata.quality.benchmark

/**
 * Typed decoding of the debug probe's external JVM marker method names.
 * Unknown methods have no operation-boundary meaning and produce no marker.
 */
internal enum class RemoteProbeMarker(private val methodName: String) {
    Begin("begin"),
    Finish("finish"),
    ;

    /**
     * Decodes externally reported names before an observer dispatches operation lifetime behavior.
     */
    companion object {
        fun decode(methodName: String): RemoteProbeMarker? = entries.find { it.methodName.contentEquals(methodName) }
    }
}
