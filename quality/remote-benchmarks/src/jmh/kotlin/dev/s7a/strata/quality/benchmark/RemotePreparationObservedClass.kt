package dev.s7a.strata.quality.benchmark

/**
 * Standard-debugger class filters decoded at the external observation boundary.
 * Each row counts actual object identities, including delegated constructor calls only once.
 */
internal enum class RemotePreparationObservedClass(val externalName: String, val reportField: String) {
    Node("dev.s7a.strata.runtime.remote.RemotePreparedTree\$Node", "prepared_node_instances"),
    Component("dev.s7a.strata.runtime.remote.RemotePreparedTree\$Component", "component_factory_instances"),
    Modifier("dev.s7a.strata.runtime.remote.RemotePreparedTree\$ActiveModifier", "modifier_factory_instances"),
    Context("dev.s7a.strata.runtime.remote.RemotePreparationContext", "preparation_context_instances"),
    ;

    companion object {
        /**
         * Converts a debugger-provided binary class name into a selected construction kind.
         */
        fun decode(name: String): RemotePreparationObservedClass? = entries.firstOrNull { it.externalName == name }
    }
}
