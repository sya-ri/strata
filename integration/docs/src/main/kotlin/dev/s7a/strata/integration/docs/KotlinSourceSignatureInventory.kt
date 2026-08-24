package dev.s7a.strata.integration.docs

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Extracts human-readable Kotlin declarations and pairs them with the independently compiled API inventory.
 *
 * The source representation preserves parameter names and default expressions that JVM reflection does not expose.
 * Compiled method counts remain the admission boundary, so a source-only or binary-only declaration fails generation.
 */
internal object KotlinSourceSignatureInventory {
    /**
     * Immutable Kotlin signature groups used by generated references.
     *
     * @property components standard component declarations keyed by typed identity.
     * @property modifiers top-level modifier declarations keyed by extension name.
     * @property parentScopeModifiers layout-parent declarations keyed by typed scope identity.
     * @property stateAndBindings curated owner-aware state and binding declarations keyed by public type name.
     */
    internal data class Result(
        internal val components: Map<DocumentedComponent, List<String>>,
        internal val modifiers: Map<String, List<String>>,
        internal val parentScopeModifiers: Map<ModifierInventory.ParentScopeModifier, List<String>>,
        internal val stateAndBindings: Map<String, List<OwnedDeclaration>>,
    )

    /**
     * One public source declaration paired with the exact nested type that owns it.
     *
     * @property ownerPath source-level owner path beginning with the cataloged top-level type.
     * @property kind declaration category used for exact binary pairing.
     * @property name declared member or type name, or an empty value for a constructor.
     * @property signature normalized Kotlin declaration without the redundant `public` modifier.
     */
    internal data class OwnedDeclaration(
        internal val ownerPath: String,
        internal val kind: DeclarationKind,
        internal val name: String,
        internal val signature: String,
    )

    /**
     * Source declaration categories whose binary owners and member identities are validated independently.
     */
    internal enum class DeclarationKind {
        /**
         * Public top-level or nested type.
         */
        TYPE,

        /**
         * Public constructor owned by its enclosing type.
         */
        CONSTRUCTOR,

        /**
         * Public function owned by its enclosing type.
         */
        FUNCTION,

        /**
         * Public property owned by its enclosing type.
         */
        PROPERTY,
    }

    /**
     * Extracts all relevant declarations below the trusted API Kotlin source root.
     *
     * @param apiSourceRoot `api/src/main/kotlin` directory.
     * @return deterministic declarations grouped by public API identity.
     * @throws IllegalArgumentException when files, declarations, or identities violate the source contract.
     */
    internal fun discover(apiSourceRoot: Path): Result {
        val root = apiSourceRoot.toAbsolutePath().normalize()
        ShowcasePaths.requireDirectory(root, "API Kotlin source root")
        val componentDirectory = root.resolve("dev/s7a/strata/component")
        val modifierDirectory = root.resolve("dev/s7a/strata/modifier")
        ShowcasePaths.requireDirectory(componentDirectory, "API component source directory")
        ShowcasePaths.requireDirectory(modifierDirectory, "API modifier source directory")

        val componentGroups =
            kotlinFiles(componentDirectory)
                .flatMap { file -> declarations(file, "UiScope") }
                .groupBy { declaration -> declaration.name }
        val unknownComponent = componentGroups.keys.firstOrNull { name -> DocumentedComponent.fromApiMethodName(name) == null }
        require(unknownComponent == null) { "API source contains an undocumented component declaration: $unknownComponent" }
        val components =
            DocumentedComponent.entries
                .filter { component -> componentGroups.containsKey(component.apiMethodName) }
                .associateWith { component -> componentGroups.getValue(component.apiMethodName).map(Declaration::signature).sorted() }

        val modifiers =
            kotlinFiles(modifierDirectory)
                .filter { file -> file.fileName.toString().endsWith("Extensions.kt") }
                .flatMap { file -> declarations(file, "Modifier") }
                .groupBy(Declaration::name, Declaration::signature)
                .mapValues { (_, signatures) -> signatures.sorted() }
                .toSortedMap()

        val parentScopeModifiers =
            ModifierInventory.ParentScopeModifier.entries.associateWith { entry ->
                val source = componentDirectory.resolve("${entry.scopeName}.kt")
                require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "Parent-scope source is missing: $source" }
                declarations(source, "Modifier")
                    .filter { declaration -> declaration.name == entry.methodName }
                    .map(Declaration::signature)
                    .sorted()
            }
        require(parentScopeModifiers.values.all(List<String>::isNotEmpty)) { "API source is missing a documented parent-scope modifier declaration." }
        val stateAndBindings =
            StateBindingDocumentationCatalog.entries.associate { entry ->
                val source = root.resolve(entry.packageName.replace('.', '/')).resolve(entry.sourceFile)
                require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "State or binding source is missing: $source" }
                val signatures = publicDeclarations(source, entry.typeName)
                require(signatures.isNotEmpty()) { "State or binding source contains no public declarations: $source" }
                entry.typeName to signatures
            }
        return Result(components, modifiers, parentScopeModifiers, stateAndBindings)
    }

    private fun kotlinFiles(directory: Path): List<Path> =
        Files.walk(directory).use { stream ->
            stream
                .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.fileName.toString().endsWith(".kt") }
                .peek { path -> ShowcasePaths.requireSafeSegments(path, "API Kotlin source") }
                .sorted()
                .toList()
        }

    private fun declarations(
        sourceFile: Path,
        receiver: String,
    ): List<Declaration> {
        ShowcasePaths.requireSafeSegments(sourceFile, "API Kotlin source file")
        require(Files.isSymbolicLink(sourceFile).not()) { "API Kotlin source file is symbolic: $sourceFile" }
        val source = Files.readString(sourceFile, StandardCharsets.UTF_8)
        require(source.startsWith('\uFEFF').not()) { "API Kotlin source contains a UTF-8 BOM: $sourceFile" }
        val pattern =
            Regex(
                "(?m)^[ \\t]*public fun(?:[ \\t]+<[^>\\r\\n]+>)?[ \\t]+${Regex.escape(receiver)}\\.([A-Za-z][A-Za-z0-9]*)[ \\t]*\\(",
            )
        return pattern
            .findAll(source)
            .map { match ->
                val name = match.groupValues[1]
                val openParenthesis = source.indexOf('(', match.range.first)
                require(openParenthesis in match.range) { "API declaration has no opening parenthesis: $sourceFile:$name" }
                val closeParenthesis = closingParenthesis(source, openParenthesis, sourceFile, name)
                val declarationEnd = declarationEnd(source, closeParenthesis, sourceFile, name)
                val signature =
                    source
                        .substring(match.range.first, declarationEnd)
                        .normalizeSignature()
                        .removePrefix("public ")
                require(signature.startsWith("fun ")) { "API declaration did not normalize to a Kotlin function: $sourceFile:$name" }
                Declaration(name, signature)
            }.toList()
    }

    private fun publicDeclarations(
        sourceFile: Path,
        topLevelTypeName: String,
    ): List<OwnedDeclaration> {
        ShowcasePaths.requireSafeSegments(sourceFile, "state and binding source file")
        require(Files.isSymbolicLink(sourceFile).not()) { "State or binding source file is symbolic: $sourceFile" }
        val source = Files.readString(sourceFile, StandardCharsets.UTF_8)
        require(source.startsWith('\uFEFF').not()) { "State or binding source contains a UTF-8 BOM: $sourceFile" }
        val pattern =
            Regex(
                "(?m)^([ \\t]*)public (?:(?:inline|operator|data|sealed|value|enum|reified|noinline|companion)[ \\t]+)*(?:fun[ \\t]+interface|class|interface|object|constructor|fun|val|var)\\b",
            )
        val owners = mutableListOf<SourceOwner>()
        val declarations = mutableListOf<OwnedDeclaration>()
        pattern.findAll(source).forEach { match ->
            val indentation = match.groupValues[1].length
            while (owners.lastOrNull()?.indentation?.let { ownerIndentation -> indentation <= ownerIndentation } == true) {
                owners.removeLast()
            }
            val start = match.range.first
            val end = publicDeclarationEnd(source, start)
            val signature =
                source
                    .substring(start, end)
                    .normalizeSignature()
                    .removePrefix("public ")
                    .trimEnd(',', ';')
            if (signature.isBlank()) return@forEach
            val identity = declarationIdentity(signature)
            val ownerPath =
                if (identity.kind == DeclarationKind.TYPE) {
                    listOfNotNull(owners.lastOrNull()?.path, identity.name).joinToString(".")
                } else {
                    owners.lastOrNull()?.path
                        ?: error("Public state or binding member has no owning type: $sourceFile:$signature")
                }
            if (identity.kind == DeclarationKind.TYPE) {
                owners += SourceOwner(indentation, ownerPath)
            }
            if (hasInternalRuntimeAnnotation(source, match.range.first).not()) {
                require(ownerPath == topLevelTypeName || ownerPath.startsWith("$topLevelTypeName.")) {
                    "State or binding declaration escaped its cataloged owner $topLevelTypeName: $ownerPath"
                }
                declarations += OwnedDeclaration(ownerPath, identity.kind, identity.name, signature)
            }
        }
        return declarations.distinct().sortedWith(compareBy(OwnedDeclaration::ownerPath, OwnedDeclaration::signature))
    }

    private fun declarationIdentity(signature: String): DeclarationIdentity {
        if (signature.startsWith("constructor")) return DeclarationIdentity(DeclarationKind.CONSTRUCTOR, "")
        if (Regex("\\bfun\\b").containsMatchIn(signature) && signature.contains('(')) {
            val functionName =
                Regex("([A-Za-z][A-Za-z0-9]*)\\s*$")
                    .find(signature.substringBefore('('))
                    ?.groupValues
                    ?.get(1)
                    ?: error("Public state or binding function has no name: $signature")
            return DeclarationIdentity(DeclarationKind.FUNCTION, functionName)
        }
        if (signature.startsWith("companion object")) {
            val name =
                Regex("^companion object(?:\\s+([A-Za-z][A-Za-z0-9]*))?")
                    .find(signature)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            return DeclarationIdentity(DeclarationKind.TYPE, name.ifBlank { "Companion" })
        }
        val type =
            Regex("\\b(?:class|interface|object)\\s+([A-Za-z][A-Za-z0-9]*)")
                .find(signature)
        if (type != null) return DeclarationIdentity(DeclarationKind.TYPE, type.groupValues[1])
        val property =
            Regex("\\b(?:val|var)\\s+([A-Za-z][A-Za-z0-9]*)")
                .find(signature)
                ?: error("Unsupported public state or binding declaration: $signature")
        return DeclarationIdentity(DeclarationKind.PROPERTY, property.groupValues[1])
    }

    private fun hasInternalRuntimeAnnotation(
        source: String,
        declarationStart: Int,
    ): Boolean {
        val prefixStart = maxOf(0, declarationStart - 96)
        return Regex("@InternalStrataRuntimeApi\\s*$").containsMatchIn(source.substring(prefixStart, declarationStart))
    }

    private fun publicDeclarationEnd(
        source: String,
        start: Int,
    ): Int {
        var parentheses = 0
        var angles = 0
        var index = start
        while (index < source.length) {
            val character = source[index]
            when (character) {
                '(' -> parentheses += 1
                ')' -> parentheses -= 1
                '<' -> angles += 1
                '>' -> if (0 < angles) angles -= 1
                '{', '=' -> if (parentheses == 0 && angles == 0) return index
                ',', '\n', '\r' -> if (parentheses == 0 && angles == 0) return index
            }
            index += 1
        }
        return source.length
    }

    private fun closingParenthesis(
        source: String,
        openParenthesis: Int,
        sourceFile: Path,
        name: String,
    ): Int {
        var depth = 0
        var index = openParenthesis
        while (index < source.length) {
            when (source[index]) {
                '(' -> {
                    depth += 1
                }

                ')' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
            index += 1
        }
        throw IllegalArgumentException("API declaration has unbalanced parameters: $sourceFile:$name")
    }

    private fun declarationEnd(
        source: String,
        closeParenthesis: Int,
        sourceFile: Path,
        name: String,
    ): Int {
        var index = closeParenthesis + 1
        while (index < source.length && source[index].isWhitespace()) index += 1
        if (source.getOrNull(index) != ':') return closeParenthesis + 1
        index += 1
        while (index < source.length && source[index] != '=' && source[index] != '{') index += 1
        require(index < source.length) { "API declaration return type has no body boundary: $sourceFile:$name" }
        return index
    }

    private fun String.normalizeSignature(): String =
        trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\(\\s+"), "(")
            .replace(Regex(",\\s*\\)"), ")")

    private data class Declaration(
        val name: String,
        val signature: String,
    )

    private data class DeclarationIdentity(
        val kind: DeclarationKind,
        val name: String,
    )

    private data class SourceOwner(
        val indentation: Int,
        val path: String,
    )
}
