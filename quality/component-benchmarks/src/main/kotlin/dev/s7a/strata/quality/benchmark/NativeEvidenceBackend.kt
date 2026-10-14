package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject

/**
 * Requested native comparison backend; actual loaded metadata must agree with this decoded value.
 */
internal enum class NativeEvidenceBackend {
    OpenGl,
    Vulkan,
    ;

    /**
     * Refuses driver fallback or missing actual backend metadata before comparison admission.
     */
    internal fun verify(report: JsonObject) {
        require(decode(report.get("native_backend").asString) == this) { "The native comparison backend did not load" }
    }

    /**
     * Decodes external request and receipt labels without using strings as native domain state.
     */
    internal companion object {
        /**
         * Requires a supported backend, preserving the distinction between Vulkan and OpenGL.
         */
        internal fun decode(value: String): NativeEvidenceBackend = requireNotNull(entries.singleOrNull { it.name.equals(value, ignoreCase = true) }) { "Unknown native evidence backend" }
    }
}
