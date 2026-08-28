package dev.s7a.strata.integration.docs

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Recursively verifies repository-local links and publication-safe release-note links.
 */
internal object DocumentationLinkChecker {
    /**
     * Checks README, docs, and public-skill Markdown and HTML links without requesting external URLs.
     * Markdown below docs/releases requires absolute HTTP(S) URLs for cross-document links because release services receive it verbatim.
     * Same-document fragments and fenced examples retain their existing treatment.
     *
     * @param args one trusted repository-root argument.
     * @throws IllegalArgumentException when a link is malformed, a local target is unsafe or absent, or a release link is not portable.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1) { "Documentation link checker requires exactly one repository root." }
        val projectRoot = Path.of(args.single()).toAbsolutePath().normalize()
        ShowcasePaths.requireDirectory(projectRoot, "documentation project root")
        val documents =
            buildList {
                add(projectRoot.resolve("README.md"))
                addAll(documentFiles(projectRoot.resolve("docs")))
                addAll(documentFiles(projectRoot.resolve("skills")))
            }.distinct().sorted()
        documents.forEach { document -> checkDocument(projectRoot, document) }
    }

    private fun documentFiles(root: Path): List<Path> {
        ShowcasePaths.requireDirectory(root, "documentation tree")
        return Files.walk(root).use { stream ->
            stream
                .filter { path ->
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        (path.fileName.toString().endsWith(".md") || path.fileName.toString().endsWith(".html"))
                }.peek { path -> ShowcasePaths.requireSafeSegments(path, "documentation source") }
                .toList()
        }
    }

    private fun checkDocument(
        projectRoot: Path,
        document: Path,
    ) {
        require(Files.isSymbolicLink(document).not()) { "Documentation file is symbolic: $document" }
        val text = Files.readString(document, StandardCharsets.UTF_8)
        val prose = removeFencedCode(text)
        val markdownTargets = Regex("!?\\[[^]]*]\\(([^)]+)\\)").findAll(prose).map { match -> match.groupValues[1] }
        val htmlTargets = Regex("(?:src|href)=\"([^\"]+)\"").findAll(prose).map { match -> match.groupValues[1] }
        (markdownTargets + htmlTargets).forEach { rawTarget -> checkTarget(projectRoot, document, rawTarget) }
    }

    private fun removeFencedCode(text: String): String {
        var fenced = false
        return text
            .lineSequence()
            .joinToString("\n") { line ->
                if (line.trimStart().startsWith("```")) {
                    fenced = fenced.not()
                    ""
                } else if (fenced) {
                    ""
                } else {
                    line
                }
            }
    }

    private fun checkTarget(
        projectRoot: Path,
        document: Path,
        rawTarget: String,
    ) {
        val target = rawTarget.trim().removeSurrounding("<", ">")
        if (target.isEmpty() || target.startsWith('#')) return
        val uri = runCatching { URI(target.substringBefore(' ')) }.getOrElse { error -> throw IllegalArgumentException("Malformed documentation link in $document: $target", error) }
        requirePortableReleaseLink(projectRoot, document, target, uri)
        if (uri.isAbsolute || uri.scheme != null) return
        val pathText = target.substringBefore('#').substringBefore('?')
        if (pathText.isEmpty()) return
        val decodedPath = URI(pathText).path
        val resolved =
            document.parent
                .resolve(decodedPath)
                .normalize()
                .toAbsolutePath()
        require(resolved.startsWith(projectRoot)) { "Documentation link escapes the repository: $document -> $target" }
        require(Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) { "Documentation link target is missing: $document -> $target" }
        require(Files.isSymbolicLink(resolved).not()) { "Documentation link target is symbolic: $document -> $target" }
    }

    private fun requirePortableReleaseLink(
        projectRoot: Path,
        document: Path,
        target: String,
        uri: URI,
    ) {
        val releaseMarkdown =
            document.startsWith(projectRoot.resolve("docs/releases")) &&
                document.fileName.toString().endsWith(".md")
        if (releaseMarkdown.not()) return
        val webScheme = uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)
        require(webScheme && uri.host.isNullOrEmpty().not()) {
            "Release notes require absolute HTTP(S) URLs for cross-document links: $document -> $target"
        }
    }
}
