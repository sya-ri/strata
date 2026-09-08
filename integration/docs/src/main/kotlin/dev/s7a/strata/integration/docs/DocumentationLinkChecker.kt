package dev.s7a.strata.integration.docs

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Synchronously verifies repository-local documentation links without requesting external URLs or modifying sources.
 * Published release bodies retain portable-link validation without depending on the current document layout.
 */
internal object DocumentationLinkChecker {
    /**
     * Checks root reader documents, implementation instructions, docs, and public-skill Markdown and HTML links.
     * Current GitHub master links resolve against the repository; immutable revisions and other external URLs are not fetched.
     * Markdown below docs/releases requires absolute HTTP(S) URLs for cross-document links because release services receive it verbatim.
     * Fenced examples are ignored, and historical release bodies do not acquire current path or fragment requirements.
     *
     * @param args one trusted repository-root argument.
     * @throws IllegalArgumentException when a link is malformed, a local target or fragment is unsafe or absent, or a release link is not portable.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1) { "Documentation link checker requires exactly one repository root." }
        val projectRoot = Path.of(args.single()).toAbsolutePath().normalize()
        ShowcasePaths.requireDirectory(projectRoot, "documentation project root")
        val documents =
            buildList {
                addAll(ROOT_DOCUMENTS.map { name -> projectRoot.resolve(name) })
                addAll(documentFiles(projectRoot.resolve("docs")))
                addAll(documentFiles(projectRoot.resolve("skills")))
            }.distinct().sorted()
        documents.forEach { document -> checkDocument(projectRoot, document) }
    }

    private fun documentFiles(root: Path): List<Path> {
        ShowcasePaths.requireDirectory(root, "documentation tree")
        return Files.walk(root).use { stream ->
            stream
                .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && isDocument(path) }
                .peek { path -> ShowcasePaths.requireSafeSegments(path, "documentation source") }
                .toList()
        }
    }

    private fun checkDocument(
        projectRoot: Path,
        document: Path,
    ) {
        ShowcasePaths.requireRegularFile(document, "documentation source")
        val text = Files.readString(document, StandardCharsets.UTF_8)
        DocumentationMarkdown.targets(text).forEach { target -> checkTarget(projectRoot, document, target) }
    }

    private fun checkTarget(
        projectRoot: Path,
        document: Path,
        target: String,
    ) {
        if (target.isEmpty()) return
        val uri =
            runCatching { URI(target) }.getOrElse { error ->
                throw IllegalArgumentException("Malformed documentation link in $document: $target", error)
            }
        if (isReleaseMarkdown(projectRoot, document)) {
            requirePortableReleaseLink(document, target, uri)
            return
        }
        val resolved = resolveLocalTarget(projectRoot, document, uri) ?: return
        require(resolved.startsWith(projectRoot)) { "Documentation link escapes the repository: $document -> $target" }
        require(Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) { "Documentation link target is missing: $document -> $target" }
        ShowcasePaths.requireSafeSegments(resolved, "documentation link target")
        checkFragment(document, resolved, uri)
    }

    private fun resolveLocalTarget(
        projectRoot: Path,
        document: Path,
        uri: URI,
    ): Path? {
        if (uri.isAbsolute || uri.rawAuthority != null) {
            if (DocumentationUriScheme.decode(uri.scheme) != DocumentationUriScheme.HTTPS || uri.host.equals(GITHUB_HOST, ignoreCase = true).not()) return null
            val match = CURRENT_GITHUB_PATH.matchEntire(uri.path.orEmpty()) ?: return null
            return projectRoot.resolve(match.groupValues[1]).normalize()
        }
        val path = uri.path.orEmpty()
        return if (path.isEmpty()) {
            document
        } else {
            document.parent
                .resolve(path)
                .normalize()
                .toAbsolutePath()
        }
    }

    private fun checkFragment(
        document: Path,
        resolved: Path,
        uri: URI,
    ) {
        val fragment = uri.fragment.orEmpty()
        if (fragment.isEmpty()) return
        val target = if (Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) resolved.resolve("README.md") else resolved
        if (isDocument(target).not()) return
        ShowcasePaths.requireRegularFile(target, "documentation fragment target")
        val text = Files.readString(target, StandardCharsets.UTF_8)
        val markdown = target.fileName.toString().endsWith(".md")
        require(fragment in DocumentationMarkdown.anchors(text, markdown)) { "Documentation link anchor is missing: $document -> $uri" }
    }

    private fun isDocument(path: Path): Boolean = path.fileName.toString().let { name -> name.endsWith(".md") || name.endsWith(".html") }

    private fun isReleaseMarkdown(
        projectRoot: Path,
        document: Path,
    ): Boolean = document.startsWith(projectRoot.resolve("docs/releases")) && document.fileName.toString().endsWith(".md")

    private fun requirePortableReleaseLink(
        document: Path,
        target: String,
        uri: URI,
    ) {
        if (target.startsWith('#')) return
        val webScheme = uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)
        require(webScheme && uri.host.isNullOrEmpty().not()) {
            "Release notes require absolute HTTP(S) URLs for cross-document links: $document -> $target"
        }
    }

    private val ROOT_DOCUMENTS = listOf("README.md", "AGENTS.md", "CONTRIBUTING.md", "CHANGELOG.md")
    private val CURRENT_GITHUB_PATH = Regex("/sya-ri/strata/(?:blob|tree)/master(?:/(.*))?")
    private const val GITHUB_HOST = "github.com"
}
