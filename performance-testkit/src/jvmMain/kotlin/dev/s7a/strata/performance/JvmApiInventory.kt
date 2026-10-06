package dev.s7a.strata.performance

import java.lang.invoke.MethodType
import java.lang.reflect.Modifier
import java.net.URI
import java.nio.file.Path

/**
 * Discovers the JVM-visible public/protected binary surface from the artifacts actually loaded by a host.
 * Includes Kotlin declarations exposed publicly in bytecode; source-level visibility is not inferred.
 * Discovery does not initialize classes and rejects shadowed resources, origins, or unresolved linkage.
 */
public object JvmApiInventory {
    // Inventory uses the same archive limits as loaded-code provenance.
    private const val MAX_CLASS_ENTRIES = 16_384
    private const val MAX_CLASS_ENTRY_BYTES = 8L * 1024 * 1024

    /**
     * Returns exact class/member identities including JVM overload descriptors, grouped by registered module.
     * The module-to-representative map is descriptive input; it never substitutes a differently named distribution JAR.
     */
    public fun capture(
        loader: ClassLoader,
        representatives: Map<String, String>,
    ): Map<String, Set<String>> {
        require(representatives.isNotEmpty() && representatives.keys.all(String::isNotBlank))
        val metadata = LoadedArtifactMetadata.capture(loader, representatives, representatives.keys)
        LoadedArtifactMetadata.verifyComplete(metadata)
        return metadata.getAsJsonArray("modules").associate { entry ->
            val module = entry.asJsonObject
            val origin = localPath(URI(module.getAsJsonObject("codeSource").get("url").asString))
            val classes = ClassArchiveInventory.entries(origin, MAX_CLASS_ENTRIES, MAX_CLASS_ENTRY_BYTES)
            val symbols =
                classes
                    .flatMap { resource ->
                        val type = Class.forName(resource.removeSuffix(".class").replace('/', '.'), false, loader)
                        check(localPath(checkNotNull(type.protectionDomain?.codeSource?.location).toURI()) == origin) {
                            "API inventory class was loaded from another artifact: ${type.name}"
                        }
                        if (isAccessible(type)) declarations(type) else emptyList()
                    }.toSortedSet()
            require(symbols.isNotEmpty()) { "The loaded module has no public binary surface: $origin" }
            module.get("module").asString to symbols.toSet()
        }
    }

    /**
     * Selects every nonsynthetic public static UpperCamel void extension on the exact caller-supplied receiver type.
     * Returns raw binary identities, preserving generated aliases and overload descriptors for reviewed assignments.
     */
    public fun componentEntryPoints(
        loader: ClassLoader,
        representatives: Map<String, String>,
        receiverBinaryName: String,
    ): Map<String, Set<String>> {
        require(receiverBinaryName.isNotBlank())
        val receiver = "(L${receiverBinaryName.replace('.', '/')};"
        val methodIdentity = Regex("^[^#]+#method:([0-9]+):([^:]+):(.*)$")
        return capture(loader, representatives).mapValues { (_, symbols) ->
            symbols
                .filter { symbol ->
                    val method = methodIdentity.matchEntire(symbol) ?: return@filter false
                    val modifiers = method.groupValues[1].toInt()
                    val name = method.groupValues[2]
                    val descriptor = method.groupValues[3]
                    Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && name.firstOrNull() in 'A'..'Z' && descriptor.startsWith(receiver) && descriptor.endsWith(")V")
                }.toSet()
        }
    }

    private fun localPath(uri: URI): Path {
        require(LocalResourceProtocol.decode(uri.scheme) == LocalResourceProtocol.File && uri.rawAuthority.isNullOrEmpty()) { "Non-local inventory artifact: $uri" }
        return Path.of(uri).toAbsolutePath().normalize()
    }

    private fun isAccessible(type: Class<*>): Boolean = accessible(type.modifiers) && (type.enclosingClass?.let(::isAccessible) ?: true)

    private fun accessible(modifiers: Int): Boolean = Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)

    private fun declarations(type: Class<*>): List<String> =
        buildList {
            val prefix = type.name
            add("$prefix#type:${type.modifiers}:${type.superclass?.name.orEmpty()}:${type.interfaces.map { it.name }.sorted().joinToString(",")}")
            type.declaredConstructors.filter { accessible(it.modifiers) && it.isSynthetic.not() }.forEach { constructor ->
                val descriptor = MethodType.methodType(Void.TYPE, constructor.parameterTypes.toList()).toMethodDescriptorString()
                add("$prefix#constructor:${constructor.modifiers}:$descriptor")
            }
            type.declaredMethods.filter { accessible(it.modifiers) && it.isSynthetic.not() }.forEach { method ->
                val descriptor = MethodType.methodType(method.returnType, method.parameterTypes.toList()).toMethodDescriptorString()
                add("$prefix#method:${method.modifiers}:${method.name}:$descriptor")
            }
            type.declaredFields.filter { accessible(it.modifiers) && it.isSynthetic.not() }.forEach { field ->
                val descriptor = MethodType.methodType(field.type).toMethodDescriptorString().removePrefix("()")
                add("$prefix#field:${field.modifiers}:${field.name}:$descriptor")
            }
        }
}
