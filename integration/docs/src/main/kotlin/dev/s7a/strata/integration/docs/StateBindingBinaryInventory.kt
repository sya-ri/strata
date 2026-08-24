package dev.s7a.strata.integration.docs

import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Captures exact public JVM type and member fingerprints for curated state and binding API groups.
 */
internal object StateBindingBinaryInventory {
    /**
     * Discovers public binary fingerprints without initializing API classes.
     *
     * @param classDirectories compiled API output directories.
     * @return fingerprints keyed by the public source type group rendered by the skill.
     */
    internal fun discover(classDirectories: List<Path>): Map<String, List<String>> {
        val directories = classDirectories.map { directory -> directory.toAbsolutePath().normalize() }
        require(directories.isNotEmpty()) { "State and binding binary inventory requires API class directories." }
        directories.forEach { directory -> ShowcasePaths.requireDirectory(directory, "state and binding API class directory") }
        val classNames =
            directories
                .flatMap { directory ->
                    Files.walk(directory).use { stream ->
                        stream
                            .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toString().endsWith(CLASS_SUFFIX) }
                            .map { path ->
                                ShowcasePaths.requireSafeSegments(path, "state and binding API class tree")
                                directory
                                    .relativize(path)
                                    .toString()
                                    .replace('\\', '.')
                                    .replace('/', '.')
                                    .removeSuffix(CLASS_SUFFIX)
                            }.toList()
                    }
                }.sorted()
        require(classNames.toSet().size == classNames.size) { "State and binding API class binary names are duplicated." }
        val loader = ApiClassLoader(directories.map { directory -> directory.toUri().toURL() }.toTypedArray(), StateBindingBinaryInventory::class.java.classLoader)
        return loader.use {
            StateBindingDocumentationCatalog.entries.associate { entry ->
                val baseName = "${entry.packageName}.${entry.typeName}"
                val groupNames = classNames.filter { className -> className == baseName || className.startsWith(baseName + '$') }
                require(groupNames.contains(baseName)) { "Compiled state or binding type is missing: $baseName" }
                val fingerprints =
                    groupNames
                        .map { className -> loadClass(loader, className) }
                        .filter { type -> Modifier.isPublic(type.modifiers) }
                        .flatMap(::fingerprints)
                        .sorted()
                require(fingerprints.isNotEmpty()) { "Compiled state or binding API has no public fingerprints: $baseName" }
                entry.typeName to fingerprints
            }
        }
    }

    private fun fingerprints(type: Class<*>): List<String> =
        buildList {
            val typeKind =
                if (type.isInterface) {
                    "interface"
                } else if (type.isEnum) {
                    "enum"
                } else {
                    "class"
                }
            add("$typeKind ${type.name}")
            type.declaredConstructors
                .filter { constructor -> Modifier.isPublic(constructor.modifiers) && constructor.isSynthetic.not() }
                .forEach { constructor -> add("constructor ${type.name}${parameters(constructor.parameterTypes)}") }
            type.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) && method.isSynthetic.not() && method.isBridge.not() }
                .forEach { method -> add("method ${type.name}.${method.name}${parameters(method.parameterTypes)}: ${typeName(method.returnType)}") }
            type.declaredFields
                .filter { field -> Modifier.isPublic(field.modifiers) && field.isSynthetic.not() }
                .forEach { field -> add("field ${type.name}.${field.name}: ${typeName(field.type)}") }
        }

    private fun parameters(types: Array<Class<*>>): String = types.joinToString(prefix = "(", postfix = ")") { type -> typeName(type) }

    private fun typeName(type: Class<*>): String = if (type.isArray) "${typeName(type.componentType)}[]" else type.name

    private fun loadClass(
        loader: ClassLoader,
        className: String,
    ): Class<*> =
        try {
            Class.forName(className, false, loader)
        } catch (error: ClassNotFoundException) {
            throw IllegalStateException("State or binding API class could not be loaded: $className", error)
        } catch (error: LinkageError) {
            throw IllegalStateException("State or binding API class linkage failed: $className", error)
        }

    private class ApiClassLoader(
        urls: Array<URL>,
        parent: ClassLoader,
    ) : URLClassLoader(urls, parent) {
        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> =
            synchronized(getClassLoadingLock(name)) {
                val loaded = findLoadedClass(name)
                if (loaded != null) return loaded
                val local = runCatching { findClass(name) }.getOrNull()
                if (local != null) {
                    if (resolve) resolveClass(local)
                    local
                } else {
                    super.loadClass(name, resolve)
                }
            }
    }

    private const val CLASS_SUFFIX = ".class"
}
