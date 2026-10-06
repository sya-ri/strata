package dev.s7a.strata.performance

/**
 * Resolves the legacy production client's window through its actual Fabric runtime namespace.
 * Intermediary owners, methods and descriptors come from the target mappings, rather than development-only names.
 */
internal object NativeMappedWindowAccess {
    /**
     * Uses the measured loader's resolver without introducing a Fabric dependency into the kit.
     */
    internal fun load(loader: ClassLoader): Pair<Any, Long> {
        val type = Class.forName("net.fabricmc.loader.api.FabricLoader", true, loader)
        val fabric = checkNotNull(HostReflection.call(type.getMethod("getInstance"), null))
        return resolve(loader, checkNotNull(HostReflection.invoke(fabric, "getMappingResolver")))
    }

    /**
     * Resolves all client/window members in one namespace; missing mappings or host methods fail preparation.
     */
    internal fun resolve(
        loader: ClassLoader,
        resolver: Any,
    ): Pair<Any, Long> {
        val clientOwner = "net.minecraft.class_310"
        val windowOwner = "net.minecraft.class_1041"
        val clientName = HostReflection.invoke(resolver, "mapClassName", "intermediary", clientOwner) as String
        val clientType = Class.forName(clientName, true, loader)

        fun method(
            owner: String,
            name: String,
            descriptor: String,
        ): String = HostReflection.invoke(resolver, "mapMethodName", "intermediary", owner, name, descriptor) as String
        val client = checkNotNull(HostReflection.call(clientType.getMethod(method(clientOwner, "method_1551", "()Lnet/minecraft/class_310;")), null))
        val window = checkNotNull(HostReflection.invoke(client, method(clientOwner, "method_22683", "()Lnet/minecraft/class_1041;")))
        val handle = HostReflection.invoke(window, method(windowOwner, "method_4490", "()J")) as Number
        return window to handle.toLong()
    }
}
