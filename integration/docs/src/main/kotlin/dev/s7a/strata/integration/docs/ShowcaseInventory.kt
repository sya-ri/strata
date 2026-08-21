package dev.s7a.strata.integration.docs

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URISyntaxException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Discovers top-level component entry points from compiled Minecraft runtime classes.
 *
 * Classes are loaded without initialization so inventory cannot run component code while inspecting declarations.
 */
internal object ShowcaseInventory {
    private const val CLASS_SUFFIX = ".class"

    /**
     * Finds every public static non-synthetic UpperCamel extension whose first JVM parameter is exactly `UiScope` and whose return type is void.
     *
     * @param classDirectories compiled Minecraft runtime directories to scan.
     * @return decoded component identities with one identity for each overload name.
     * @throws IllegalArgumentException when directories or component method declarations violate the inventory contract.
     * @throws IllegalStateException when class loading or reflection fails.
     */
    internal fun discover(classDirectories: List<Path>): Set<DocumentedComponent> {
        val directories = classDirectories.map { directory -> directory.toAbsolutePath().normalize() }
        require(directories.isNotEmpty()) { "Minecraft component class directories are missing." }
        require(directories.toSet().size == directories.size) { "Minecraft component class directories are duplicated." }
        directories.forEach { directory -> ShowcasePaths.requireDirectory(directory, "Minecraft component class directory") }
        val urls = directories.map { directory -> directory.toUri().toURL() }.toTypedArray()
        val loader = ApiClassLoader(urls, ShowcaseInventory::class.java.classLoader)
        return loader.use {
            val uiScopeType = loadClass(loader, UI_SCOPE_CLASS_NAME)
            val classNames =
                directories
                    .flatMap { directory -> classFiles(directory).map { path -> binaryName(directory, path) } }
                    .sorted()
            require(classNames.isNotEmpty()) { "Minecraft component class outputs contain no classes." }
            require(classNames.toSet().size == classNames.size) { "Minecraft component class binary names are duplicated." }
            val methods =
                classNames
                    .flatMap { className ->
                        val type = loadClass(loader, className)
                        requireClassOrigin(type, directories)
                        declaredMethods(type, className, origin(type), uiScopeType)
                    }.sortedWith(compareBy({ method -> method.declaringClass.name }, { method -> method.name }, { method -> descriptor(method) }))
                    .groupBy { method -> method.name }
                    .toSortedMap()
            require(methods.keys.all { name -> DocumentedComponent.fromApiMethodName(name) != null }) {
                "Minecraft component inventory contains an undecoded component method: ${methods.keys}."
            }
            require(methods.values.all { overloads -> overloads.isNotEmpty() }) {
                "Minecraft component inventory contains an empty component overload group."
            }
            methods.keys
                .mapNotNull { name -> DocumentedComponent.fromApiMethodName(name) }
                .toSet()
        }
    }

    private fun requireClassOrigin(
        type: Class<*>,
        directories: List<Path>,
    ) {
        val classOrigin = origin(type)
        require(directories.any { directory -> directory == classOrigin }) {
            "Minecraft component class was loaded from an unintended origin: ${type.name} origin=$classOrigin"
        }
    }

    private fun origin(type: Class<*>): Path {
        val codeSource = type.protectionDomain?.codeSource ?: error("Minecraft component class has no code origin: ${type.name}")
        return try {
            Path.of(codeSource.location.toURI()).toAbsolutePath().normalize()
        } catch (error: URISyntaxException) {
            throw IllegalStateException("Minecraft component class origin is malformed: ${type.name}", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Minecraft component class origin is malformed: ${type.name}", error)
        }
    }

    private fun loadClass(
        loader: ClassLoader,
        className: String,
    ): Class<*> =
        try {
            Class.forName(className, false, loader)
        } catch (error: ClassNotFoundException) {
            throw IllegalStateException("Minecraft component class could not be loaded without initialization: $className", error)
        } catch (error: LinkageError) {
            throw IllegalStateException("Minecraft component class linkage failed without initialization: $className", error)
        }

    private fun declaredMethods(
        type: Class<*>,
        className: String,
        origin: Path,
        uiScopeType: Class<*>,
    ): List<Method> =
        try {
            type.declaredMethods.filter { method -> isComponentMethod(method, uiScopeType) }
        } catch (error: SecurityException) {
            throw IllegalStateException("Minecraft component reflection failed for $className origin=$origin", error)
        } catch (error: LinkageError) {
            throw IllegalStateException("Minecraft component reflection linkage failed for $className origin=$origin", error)
        }

    private fun classFiles(directory: Path): List<Path> =
        Files.walk(directory).use { stream ->
            val paths = stream.toList().sortedBy { path -> path.toString() }
            paths.forEach { path ->
                ShowcasePaths.requireSafeSegments(path, "Minecraft component class tree")
                require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    "Minecraft component class tree contains a non-regular entry: $path"
                }
            }
            paths.filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toString().endsWith(CLASS_SUFFIX) }
        }

    private fun binaryName(
        directory: Path,
        classFile: Path,
    ): String {
        val parts =
            directory
                .relativize(classFile)
                .iterator()
                .asSequence()
                .map { part -> part.toString() }
                .toList()
        require(parts.isNotEmpty()) { "Minecraft component class path is empty: $classFile" }
        val last = parts.last()
        require(last.endsWith(CLASS_SUFFIX) && 6 < last.length) { "Malformed Minecraft component class path: $classFile" }
        val binaryParts = parts.dropLast(1) + last.removeSuffix(CLASS_SUFFIX)
        val invalidPart =
            binaryParts.firstOrNull { part ->
                part.isEmpty() || (part.all { character -> character == '.' } && part.length <= 2)
            }
        require(invalidPart == null) {
            "Malformed API binary path: $classFile"
        }
        return binaryParts.joinToString(".")
    }

    private fun isComponentMethod(
        method: Method,
        uiScopeType: Class<*>,
    ): Boolean {
        val parameters = method.parameterTypes
        return Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers) &&
            method.isSynthetic.not() &&
            isUpperCamel(method.name) &&
            parameters.isNotEmpty() &&
            parameters[0] == uiScopeType &&
            method.returnType == Void.TYPE
    }

    private fun isUpperCamel(value: String): Boolean {
        if (value.isEmpty()) return false
        if (value[0] in 'A'..'Z') {
            return value.drop(1).all { char -> char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' }
        }
        return false
    }

    private fun descriptor(method: Method): String = method.parameterTypes.joinToString(prefix = "(", postfix = ")") { type -> descriptor(type) } + descriptor(method.returnType)

    private fun descriptor(type: Class<*>): String =
        when {
            type == Boolean::class.javaPrimitiveType -> "Z"
            type == Byte::class.javaPrimitiveType -> "B"
            type == Char::class.javaPrimitiveType -> "C"
            type == Short::class.javaPrimitiveType -> "S"
            type == Int::class.javaPrimitiveType -> "I"
            type == Long::class.javaPrimitiveType -> "J"
            type == Float::class.javaPrimitiveType -> "F"
            type == Double::class.javaPrimitiveType -> "D"
            type == Unit::class.javaPrimitiveType -> "V"
            type.isArray -> type.name.replace('.', '/')
            else -> "L${type.name.replace('.', '/')};"
        }

    private const val UI_SCOPE_CLASS_NAME = "dev.s7a.strata.dsl.UiScope"

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
                if (loaded != null) {
                    return loaded
                }
                val local =
                    try {
                        findClass(name)
                    } catch (_: ClassNotFoundException) {
                        null
                    }
                if (local != null) {
                    if (resolve) resolveClass(local)
                    local
                } else {
                    super.loadClass(name, resolve)
                }
            }
    }
}
