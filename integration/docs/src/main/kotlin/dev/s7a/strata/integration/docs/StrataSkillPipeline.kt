package dev.s7a.strata.integration.docs

import dev.s7a.strata.integration.docs.KotlinSourceSignatureInventory.DeclarationKind
import dev.s7a.strata.integration.docs.KotlinSourceSignatureInventory.OwnedDeclaration
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator

/**
 * Produces, synchronizes, and byte-checks generated public-skill references.
 */
internal object StrataSkillPipeline {
    /**
     * Prepares every generated reference from compiled API declarations and compiled-example source.
     *
     * @param launch validated launcher inputs.
     * @return repository-relative generated paths mapped to LF UTF-8 content.
     */
    internal fun prepare(launch: StrataSkillLaunchArguments): Map<String, String> {
        val compiledComponents = ShowcaseInventory.discoverOverloads(launch.apiClassDirectories)
        require(compiledComponents.keys == DocumentedComponent.entries.toSet()) {
            "Compiled component inventory does not match the documented standard surface: ${compiledComponents.keys}"
        }
        val compiledModifiers = ModifierInventory.discover(launch.apiClassDirectories)
        val compiledStateAndBindings = StateBindingBinaryInventory.discover(launch.apiClassDirectories)
        val apiSourceRoot = launch.projectRoot.resolve("api/src/main/kotlin")
        val signatures = KotlinSourceSignatureInventory.discover(apiSourceRoot)
        validateSignatures(compiledComponents, compiledModifiers, compiledStateAndBindings, signatures)
        val openExample = example(launch.exampleSourceRoot, "OpenScreenExample.kt", "skill-open")
        val layoutExample = example(launch.exampleSourceRoot, "StructuredScreenExample.kt", "skill-layout")
        val customExample = example(launch.exampleSourceRoot, "CustomComponentExample.kt", "skill-custom")
        val versions = supportedVersions(launch.projectRoot)
        val readme = generatedReadme(launch.projectRoot, openExample)
        return linkedMapOf(
            "README.md" to readme,
            "docs/modrinth-project.md" to ModrinthProjectMarkdown.render(versions, openExample),
            "skills/strata/references/setup.md" to StrataSkillMarkdown.setup(versions, openExample),
            "skills/strata/references/components.md" to StrataSkillMarkdown.components(signatures.components),
            "skills/strata/references/modifiers-and-layout.md" to StrataSkillMarkdown.modifiers(compiledModifiers, compiledStateAndBindings, signatures),
            "skills/strata/references/patterns.md" to StrataSkillMarkdown.patterns(layoutExample),
            "skills/strata/references/custom-components.md" to StrataSkillMarkdown.customComponents(customExample),
        )
    }

    /**
     * Writes prepared references below an isolated build directory for inspectable acceptance evidence.
     *
     * @param launch validated launcher inputs.
     * @param prepared exact generated content.
     */
    internal fun writeStaging(
        launch: StrataSkillLaunchArguments,
        prepared: Map<String, String>,
    ) {
        deleteTree(launch.stagingRoot)
        Files.createDirectories(launch.stagingRoot)
        prepared.forEach { (relative, content) ->
            val destination = launch.stagingRoot.resolve(relative).normalize()
            require(destination.startsWith(launch.stagingRoot)) { "Generated skill staging path escapes its root: $relative" }
            Files.createDirectories(destination.parent)
            Files.writeString(destination, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
    }

    /**
     * Synchronizes generated references into their repository-owned destinations.
     *
     * @param projectRoot trusted repository root.
     * @param prepared exact generated content.
     */
    internal fun synchronize(
        projectRoot: Path,
        prepared: Map<String, String>,
    ) {
        prepared.forEach { (relative, content) ->
            val destination = projectRoot.resolve(relative).normalize()
            val document =
                requireNotNull(StrataGeneratedDocument.fromRelativePath(relative)) {
                    "Generated skill destination is not registered: $relative"
                }
            val expectedDestination = projectRoot.resolve(document.relativePath).normalize()
            require(destination == expectedDestination) {
                "Generated skill destination escapes its owned references and README region: $relative"
            }
            Files.createDirectories(destination.parent)
            require(Files.isSymbolicLink(destination).not()) { "Generated skill destination is symbolic: $destination" }
            Files.writeString(
                destination,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }
    }

    /**
     * Requires every checked-in generated reference to match prepared bytes.
     *
     * @param projectRoot trusted repository root.
     * @param prepared exact generated content.
     */
    internal fun check(
        projectRoot: Path,
        prepared: Map<String, String>,
    ) {
        prepared.forEach { (relative, content) ->
            val source = projectRoot.resolve(relative).normalize()
            require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "Generated Strata skill reference is missing: $relative" }
            require(Files.isSymbolicLink(source).not()) { "Generated Strata skill reference is symbolic: $relative" }
            val expected = content.toByteArray(StandardCharsets.UTF_8)
            val actual = Files.readAllBytes(source)
            require(actual.contentEquals(expected)) {
                "Generated Strata skill reference is stale: $relative. Run :integration:docs:generateStrataSkill."
            }
        }
    }

    private fun validateSignatures(
        compiledComponents: Map<DocumentedComponent, Int>,
        compiledModifiers: ModifierInventory.Result,
        compiledStateAndBindings: Map<String, List<String>>,
        signatures: KotlinSourceSignatureInventory.Result,
    ) {
        require(signatures.components.keys == compiledComponents.keys) { "Component Kotlin signature identities differ from compiled identities." }
        compiledComponents.forEach { (component, overloadCount) ->
            require(signatures.components.getValue(component).size == overloadCount) {
                "Component Kotlin signatures differ from compiled overloads for ${component.apiMethodName}."
            }
        }
        require(compiledModifiers.modifiers.size == 41) { "Expected 41 top-level modifier groups, found ${compiledModifiers.modifiers.size}." }
        require(compiledModifiers.modifiers.values.sum() == 51) { "Expected 51 top-level modifier overloads, found ${compiledModifiers.modifiers.values.sum()}." }
        require(compiledModifiers.modifiers.keys == ModifierDocumentationCatalog.entries.keys) {
            "Modifier guidance differs from the compiled modifier surface."
        }
        require(signatures.modifiers.keys == compiledModifiers.modifiers.keys) { "Modifier Kotlin signature identities differ from compiled identities." }
        compiledModifiers.modifiers.forEach { (name, overloadCount) ->
            require(signatures.modifiers.getValue(name).size == overloadCount) {
                "Modifier Kotlin signatures differ from compiled overloads for $name."
            }
        }
        require(compiledModifiers.parentScopeModifiers.keys == ModifierInventory.ParentScopeModifier.entries.toSet()) {
            "Parent-scope compiled modifiers differ from the documented surface."
        }
        compiledModifiers.parentScopeModifiers.forEach { (entry, overloadCount) ->
            require(signatures.parentScopeModifiers.getValue(entry).size == overloadCount) {
                "Parent-scope Kotlin signatures differ from compiled overloads for ${entry.scopeName}.${entry.methodName}."
            }
        }
        require(compiledStateAndBindings.keys == signatures.stateAndBindings.keys) {
            "State and binding Kotlin signature identities differ from compiled type identities."
        }
        StateBindingDocumentationCatalog.entries.forEach { entry ->
            validateStateBinaryPair(
                entry,
                signatures.stateAndBindings.getValue(entry.typeName),
                compiledStateAndBindings.getValue(entry.typeName),
            )
        }
    }

    /**
     * Requires every source declaration to exist on the exact compiled nested owner recorded by the source inventory.
     *
     * @param entry cataloged top-level API group.
     * @param signatures owner-aware source declarations for [entry].
     * @param fingerprints compiled fingerprints for the top-level type and its public nested types.
     * @throws IllegalArgumentException when source ownership or a corresponding compiled declaration differs.
     */
    internal fun validateStateBinaryPair(
        entry: StateBindingDocumentationCatalog.Entry,
        signatures: List<OwnedDeclaration>,
        fingerprints: List<String>,
    ) {
        signatures.forEach { declaration ->
            require(declaration.ownerPath == entry.typeName || declaration.ownerPath.startsWith("${entry.typeName}.")) {
                "State or binding source declaration escaped ${entry.typeName}: ${declaration.ownerPath}"
            }
            val binaryOwner = "${entry.packageName}.${declaration.ownerPath.replace('.', '$')}"
            val exists =
                when (declaration.kind) {
                    DeclarationKind.TYPE -> {
                        fingerprints.any { fingerprint ->
                            fingerprint == "class $binaryOwner" ||
                                fingerprint == "interface $binaryOwner" ||
                                fingerprint == "enum $binaryOwner"
                        }
                    }

                    DeclarationKind.CONSTRUCTOR -> {
                        fingerprints.any { fingerprint -> fingerprint.startsWith("constructor $binaryOwner(") }
                    }

                    DeclarationKind.FUNCTION -> {
                        declaration.signature.contains("inline") ||
                            fingerprints.any { fingerprint -> fingerprint.startsWith("method $binaryOwner.${declaration.name}(") }
                    }

                    DeclarationKind.PROPERTY -> {
                        val getterName = "get${declaration.name.replaceFirstChar(Char::uppercase)}"
                        fingerprints.any { fingerprint ->
                            fingerprint.startsWith("method $binaryOwner.$getterName(") ||
                                fingerprint.startsWith("field $binaryOwner.${declaration.name}:")
                        }
                    }
                }
            require(exists) {
                "State or binding source ${declaration.kind.name.lowercase()} has no compiled declaration on ${declaration.ownerPath}: ${declaration.signature}"
            }
        }
    }

    private fun example(
        sourceRoot: Path,
        fileName: String,
        slug: String,
    ): String =
        ShowcaseSources
            .extract(
                SourceReference("dev/s7a/strata/integration/docs/skill/$fileName", slug),
                sourceRoot,
            ).source

    private fun generatedReadme(
        projectRoot: Path,
        openExample: String,
    ): String {
        val readmePath = projectRoot.resolve("README.md")
        val readme = Files.readString(readmePath, StandardCharsets.UTF_8)
        val begin = "<!-- strata-api-open-example:start -->"
        val end = "<!-- strata-api-open-example:end -->"
        require(readme.split(begin).size == 2 && readme.split(end).size == 2) {
            "README must contain exactly one Strata API opening-example marker pair."
        }
        val beginIndex = readme.indexOf(begin)
        val endIndex = readme.indexOf(end)
        require(beginIndex < endIndex) { "README Strata API opening-example markers are out of order." }
        val region =
            """$begin
```kotlin
$openExample
```
$end"""
        return readme.substring(0, beginIndex) + region + readme.substring(endIndex + end.length)
    }

    private fun supportedVersions(projectRoot: Path): List<String> {
        val runtimeRoot = projectRoot.resolve("runtime")
        ShowcasePaths.requireDirectory(runtimeRoot, "runtime module root")
        val prefix = "minecraft-fabric-"
        val versions =
            Files
                .list(runtimeRoot)
                .use { stream ->
                    stream
                        .filter { path ->
                            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                                path.fileName.toString().startsWith(prefix) &&
                                path.fileName
                                    .toString()
                                    .removePrefix(prefix)
                                    .matches(Regex("[0-9]+(?:\\.[0-9]+)*"))
                        }.map { path ->
                            ShowcasePaths.requireSafeSegments(path, "Minecraft runtime module")
                            require(Files.isRegularFile(path.resolve("build.gradle.kts"), LinkOption.NOFOLLOW_LINKS)) {
                                "Minecraft runtime module has no build: $path"
                            }
                            path.fileName.toString().removePrefix(prefix)
                        }.toList()
                }.sortedBy(VersionNumber::parse)
        require(versions.size == 21) { "Strata v0.1.1 skill expects 21 working Minecraft runtimes, found ${versions.size}." }
        val decodedVersions = versions.map(VersionNumber::parse)
        require(decodedVersions.first() == VersionNumber(listOf(1, 20)) && decodedVersions.last() == VersionNumber(listOf(26, 2))) {
            "Strata v0.1.1 support boundaries changed: ${versions.firstOrNull()}..${versions.lastOrNull()}"
        }
        return versions
    }

    private data class VersionNumber(
        private val parts: List<Int>,
    ) : Comparable<VersionNumber> {
        override fun compareTo(other: VersionNumber): Int {
            val count = maxOf(parts.size, other.parts.size)
            repeat(count) { index ->
                val leftPart = parts.getOrElse(index) { 0 }
                val rightPart = other.parts.getOrElse(index) { 0 }
                if (leftPart < rightPart) return -1
                if (rightPart < leftPart) return 1
            }
            return 0
        }

        companion object {
            fun parse(value: String): VersionNumber = VersionNumber(value.split('.').map(String::toInt))
        }
    }

    private fun deleteTree(root: Path) {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS).not()) return
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Strata skill staging root is not a directory: $root" }
        Files.walk(root).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .forEach { path -> Files.delete(path) }
        }
    }
}
