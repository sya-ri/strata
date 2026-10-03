package dev.s7a.strata.performance

/**
 * Exact intermediary descriptor fixture routing to independently named loaded JVM members.
 */
internal class MappedPerformanceResolver(
    private val invalidWindow: Boolean = false,
) {
    /**
     * Accepts the reviewed intermediary owner and returns a real loaded fixture class.
     */
    fun mapClassName(
        namespace: String,
        owner: String,
    ): String {
        require(namespace.contentEquals("intermediary") && owner.contentEquals("net.minecraft.class_310"))
        return MappedPerformanceClient::class.java.name
    }

    /**
     * Routes exact descriptors, optionally exposing a missing member to exercise rejection.
     */
    fun mapMethodName(
        namespace: String,
        owner: String,
        name: String,
        descriptor: String,
    ): String {
        require(namespace.contentEquals("intermediary"))
        return when (listOf(owner, name, descriptor)) {
            listOf("net.minecraft.class_310", "method_1551", "()Lnet/minecraft/class_310;") -> "actualInstance"
            listOf("net.minecraft.class_310", "method_22683", "()Lnet/minecraft/class_1041;") -> if (invalidWindow) "missingWindow" else "actualWindow"
            listOf("net.minecraft.class_1041", "method_4490", "()J") -> "actualHandle"
            else -> error("Unregistered host descriptor")
        }
    }
}
