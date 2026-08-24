package dev.s7a.strata.integration.docs

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Validates the complete public skill package beyond generated-reference freshness.
 */
internal object StrataSkillValidator {
    /**
     * Checks package shape, encoding, metadata, licensing, and README installation commands.
     *
     * @param projectRoot trusted repository root.
     */
    internal fun validate(projectRoot: Path) {
        val skillRoot = projectRoot.resolve("skills/strata")
        ShowcasePaths.requireDirectory(skillRoot, "Strata skill root")
        require(Files.isSymbolicLink(skillRoot).not()) { "Strata skill root must not be symbolic." }
        val required =
            setOf(
                "SKILL.md",
                "LICENSE.txt",
                "agents/openai.yaml",
                "references/setup.md",
                "references/components.md",
                "references/modifiers-and-layout.md",
                "references/patterns.md",
                "references/custom-components.md",
            )
        val actual =
            Files.walk(skillRoot).use { stream ->
                stream
                    .filter { path -> path != skillRoot }
                    .peek { path ->
                        ShowcasePaths.requireSafeSegments(path, "Strata skill package")
                        require(Files.isSymbolicLink(path).not()) { "Strata skill package contains a symbolic path: $path" }
                        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                            "Strata skill package contains a non-regular path: $path"
                        }
                    }.filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                    .map { path -> skillRoot.relativize(path).toString().replace('\\', '/') }
                    .toList()
                    .toSet()
            }
        require(actual == required) { "Strata skill package files differ from the public contract: $actual" }
        required.forEach { relative -> validateText(skillRoot.resolve(relative)) }

        val license = Files.readAllBytes(projectRoot.resolve("LICENSE"))
        require(Files.readAllBytes(skillRoot.resolve("LICENSE.txt")).contentEquals(license)) {
            "Strata skill LICENSE.txt must be byte-identical to the repository LICENSE."
        }
        val entrypoint = Files.readString(skillRoot.resolve("SKILL.md"), StandardCharsets.UTF_8)
        require(entrypoint.startsWith("---\nname: strata\n")) { "Strata skill frontmatter must declare name: strata first." }
        require(entrypoint.contains("\nlicense: MIT\n")) { "Strata skill frontmatter must declare the MIT license." }
        require(entrypoint.contains("Consumer scope")) { "Strata skill must distinguish consumer authoring from Strata internals." }
        required.filter { path -> path.startsWith("references/") }.forEach { relative ->
            require(entrypoint.contains(relative.removePrefix("references/"))) { "Strata skill entrypoint does not route to $relative." }
        }
        val metadata = Files.readString(skillRoot.resolve("agents/openai.yaml"), StandardCharsets.UTF_8)
        require(metadata.contains($$"""default_prompt: "Use $strata""")) { $$"Strata skill default prompt must explicitly invoke $strata." }
        require(metadata.contains("allow_implicit_invocation: true")) { "Strata skill must retain implicit invocation." }
        val readme = Files.readString(projectRoot.resolve("README.md"), StandardCharsets.UTF_8)
        require(readme.contains("gh skill preview sya-ri/strata skills/strata")) { "README is missing the gh skill preview command." }
        require(readme.contains("npx skills add sya-ri/strata --skill strata")) { "README is missing the npx skill installation command." }
    }

    private fun validateText(path: Path) {
        val bytes = Files.readAllBytes(path)
        require(bytes.isNotEmpty()) { "Strata skill text file is empty: $path" }
        val hasBom = 3 <= bytes.size && bytes[0].toInt() and 0xFF == 0xEF && bytes[1].toInt() and 0xFF == 0xBB && bytes[2].toInt() and 0xFF == 0xBF
        require(hasBom.not()) { "Strata skill text file contains a UTF-8 BOM: $path" }
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        require(text.contains('\r').not()) { "Strata skill text file must use LF endings: $path" }
        require(text.endsWith('\n')) { "Strata skill text file must end with one newline: $path" }
        require(text.endsWith("\n\n").not()) { "Strata skill text file must end with exactly one newline: $path" }
        require(text.contains("TODO").not()) { "Strata skill text file contains an unfinished TODO: $path" }
    }
}
