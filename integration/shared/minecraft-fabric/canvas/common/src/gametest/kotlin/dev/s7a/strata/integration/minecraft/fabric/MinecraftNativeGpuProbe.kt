package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonObject

/**
 * Optional compiled native timestamp boundary for the performance fixture, never a runtime adapter requirement.
 * Each operation runs on the client render thread; implementations own their queries and terminal release.
 */
internal interface MinecraftNativeGpuProbe : AutoCloseable {
    /**
     * Arms one real GUI-consumer timestamp pair, without submitting or waiting for GPU work.
     */
    fun arm()

    /**
     * True only after every requested native query pair has been recorded and completed.
     */
    val completed: Boolean

    /**
     * Appends shared-kit distributions to complete CPU evidence after actual query completion.
     */
    fun append(report: JsonObject)
}
