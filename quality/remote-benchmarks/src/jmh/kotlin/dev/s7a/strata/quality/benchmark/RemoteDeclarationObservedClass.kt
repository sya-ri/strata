package dev.s7a.strata.quality.benchmark

/**
 * Standard-debugger class filters decoded at the external observation boundary.
 * Each row counts actual object identities, including delegated constructor calls only once.
 */
internal enum class RemoteDeclarationObservedClass(val externalName: String, val reportField: String) {
    Retained("dev.s7a.strata.runtime.spi.RuntimeDeclaration", "retained_declaration_instances"),
    Detached("dev.s7a.strata.runtime.remote.RemoteDeclaration", "detached_declaration_instances"),
    Node("dev.s7a.strata.runtime.remote.RemoteNode", "detached_node_instances"),
    ;

    companion object {
        /**
         * Converts a debugger-provided binary class name into a selected construction kind.
         */
        fun decode(name: String): RemoteDeclarationObservedClass? = entries.firstOrNull { it.externalName == name }
    }
}
