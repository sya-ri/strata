package dev.s7a.strata.integration.docs

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Discovers the public modifier extension surface from compiled API classes without initializing application code.
 */
internal object ModifierInventory {
    /**
     * Immutable overload inventory used to render and validate the public skill.
     *
     * @property modifiers top-level `Modifier` extension overloads keyed by method name.
     * @property parentScopeModifiers layout-parent modifier overloads keyed by typed scope identity.
     */
    internal data class Result(
        internal val modifiers: Map<String, Int>,
        internal val parentScopeModifiers: Map<ParentScopeModifier, Int>,
    )

    /**
     * Typed identities for the modifier functions whose meaning comes from one consuming parent layout.
     *
     * @property scopeName public layout callback scope.
     * @property methodName public extension function name.
     */
    internal enum class ParentScopeModifier(
        internal val scopeName: String,
        internal val methodName: String,
    ) {
        /**
         * Row child weight.
         */
        RowWeight("RowScope", "weight"),

        /**
         * Row child cross-axis alignment.
         */
        RowAlign("RowScope", "align"),

        /**
         * Column child weight.
         */
        ColumnWeight("ColumnScope", "weight"),

        /**
         * Column child cross-axis alignment.
         */
        ColumnAlign("ColumnScope", "align"),

        /**
         * Grid cell alignment.
         */
        GridAlign("GridScope", "align"),

        /**
         * Stack overlay alignment.
         */
        StackAlign("StackScope", "align"),
        ;

        internal val className: String
            get() = "$COMPONENT_PACKAGE.$scopeName"

        /**
         * Decodes compiled scope methods into typed parent-scope identities.
         */
        internal companion object {
            /**
             * Decodes one compiled scope and method pair.
             *
             * @param className declaring binary class name.
             * @param methodName public method name.
             * @return typed identity or null when the pair is outside the documented parent-scope surface.
             */
            internal fun from(
                className: String,
                methodName: String,
            ): ParentScopeModifier? = entries.firstOrNull { entry -> entry.className == className && entry.methodName == methodName }
        }
    }

    /**
     * Discovers top-level and layout-parent modifier overloads in compiled output.
     *
     * @param classDirectories compiled API class directories.
     * @return deterministic grouped overload inventory.
     * @throws IllegalArgumentException when directories, origins, or declarations violate the inventory boundary.
     * @throws IllegalStateException when class loading or reflection fails.
     */
    internal fun discover(classDirectories: List<Path>): Result {
        val directories = classDirectories.map { directory -> directory.toAbsolutePath().normalize() }
        require(directories.isNotEmpty()) { "API class directories are missing." }
        require(directories.toSet().size == directories.size) { "API class directories are duplicated." }
        directories.forEach { directory -> ShowcasePaths.requireDirectory(directory, "API class directory") }
        val classNames = classNames(directories)
        require(classNames.toSet().size == classNames.size) { "API class binary names are duplicated." }
        val loader = ApiClassLoader(directories.map { directory -> directory.toUri().toURL() }.toTypedArray(), ModifierInventory::class.java.classLoader)
        return loader.use {
            val modifierType = loadClass(loader, MODIFIER_CLASS_NAME)
            val topLevelMethods =
                classNames
                    .filter { className -> className.startsWith(MODIFIER_PACKAGE_PREFIX) && className.contains('$').not() }
                    .flatMap { className -> declaredMethods(loadClass(loader, className), className) }
                    .filter { method -> isTopLevelModifier(method, modifierType) }
            val scopeMethods =
                ParentScopeModifier.entries
                    .map(ParentScopeModifier::className)
                    .distinct()
                    .flatMap { className -> declaredMethods(loadClass(loader, className), className) }
                    .filter { method -> isParentScopeModifier(method, modifierType) }
            val unknownScope = scopeMethods.firstOrNull { method -> ParentScopeModifier.from(method.declaringClass.name, method.name) == null }
            require(unknownScope == null) {
                "API parent-scope modifier inventory contains an undecoded method: ${unknownScope?.declaringClass?.name}.${unknownScope?.name}."
            }
            Result(
                modifiers = topLevelMethods.groupingBy { method -> method.name.substringBefore('-') }.eachCount().toSortedMap(),
                parentScopeModifiers =
                    ParentScopeModifier.entries
                        .filter { entry -> scopeMethods.any { method -> entry.className == method.declaringClass.name && entry.methodName == method.name } }
                        .associateWith { entry -> scopeMethods.count { method -> entry.className == method.declaringClass.name && entry.methodName == method.name } },
            )
        }
    }

    private fun classNames(directories: List<Path>): List<String> =
        directories
            .flatMap { directory ->
                Files.walk(directory).use { stream ->
                    stream
                        .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toString().endsWith(CLASS_FILE_SUFFIX) }
                        .map { path ->
                            ShowcasePaths.requireSafeSegments(path, "API class tree")
                            val relative =
                                directory
                                    .relativize(path)
                                    .toString()
                                    .replace('\\', '/')
                                    .removeSuffix(CLASS_FILE_SUFFIX)
                            relative.replace('/', '.')
                        }.toList()
                }
            }.sorted()

    private fun declaredMethods(
        type: Class<*>,
        className: String,
    ): List<Method> =
        try {
            type.declaredMethods.toList()
        } catch (error: SecurityException) {
            throw IllegalStateException("API modifier reflection failed for $className.", error)
        } catch (error: LinkageError) {
            throw IllegalStateException("API modifier reflection linkage failed for $className.", error)
        }

    private fun loadClass(
        loader: ClassLoader,
        className: String,
    ): Class<*> =
        try {
            Class.forName(className, false, loader)
        } catch (error: ClassNotFoundException) {
            throw IllegalStateException("API modifier class could not be loaded without initialization: $className", error)
        } catch (error: LinkageError) {
            throw IllegalStateException("API modifier class linkage failed without initialization: $className", error)
        }

    private fun isTopLevelModifier(
        method: Method,
        modifierType: Class<*>,
    ): Boolean =
        Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers) &&
            method.isSynthetic.not() &&
            method.parameterTypes.firstOrNull() == modifierType

    private fun isParentScopeModifier(
        method: Method,
        modifierType: Class<*>,
    ): Boolean =
        Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers).not() &&
            method.isSynthetic.not() &&
            method.parameterTypes.firstOrNull() == modifierType &&
            method.returnType == modifierType

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

    private const val CLASS_FILE_SUFFIX = ".class"
    private const val COMPONENT_PACKAGE = "dev.s7a.strata.component"
    private const val MODIFIER_PACKAGE_PREFIX = "dev.s7a.strata.modifier."
    private const val MODIFIER_CLASS_NAME = "dev.s7a.strata.modifier.Modifier"
}
