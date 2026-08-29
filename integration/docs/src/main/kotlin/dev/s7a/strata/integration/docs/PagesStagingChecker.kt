package dev.s7a.strata.integration.docs

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Verifies current Dokka entrypoints, their local HTML links, and canonical source receipts.
 * Reads staged files synchronously without crawling or modifying immutable release snapshots.
 */
internal object PagesStagingChecker {
    /**
     * Checks one repository source tree against one complete staged Pages root.
     *
     * @param args repository root, staged Pages root, and generated inventory file.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 3) { "Pages staging checker requires repository, staged-site, and inventory paths." }
        check(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]))
    }

    /**
     * Finds current HTML entrypoints linked directly from the staged Dokka root, including nested module paths.
     *
     * Resolves browser paths without following symbolic links or crawling linked pages.
     *
     * @param siteRoot normalized absolute staged Pages root.
     * @return contained public HTML paths and directly linked snapshot targets without inspecting snapshot contents.
     * @throws IllegalArgumentException when a root link is unsafe or advertises reader Markdown.
     */
    internal fun rootEntrypointPaths(siteRoot: Path): Set<String> {
        val document = siteRoot.resolve(INDEX_FILE)
        ShowcasePaths.requireRegularFile(document, "Dokka root entrypoint")
        return htmlTargets(document)
            .mapNotNull { uri -> currentTarget(siteRoot, document, uri) }
            .filter { target -> target.toString().endsWith(HTML_SUFFIX, ignoreCase = true) || isSnapshotTarget(siteRoot, target) }
            .map { target -> "/${siteRoot.relativize(target).toString().replace('\\', '/')}" }
            .toSet()
    }

    /**
     * Resolves inventoried files and their direct HTML links, assets, and anchors without following symbolic links.
     * Does not infer browser anchors from Markdown or traverse linked HTML pages recursively.
     *
     * @param projectRoot trusted repository root.
     * @param siteRoot complete staged Pages root.
     * @param inventoryFile generated public relative URL inventory.
     */
    internal fun check(
        projectRoot: Path,
        siteRoot: Path,
        inventoryFile: Path,
    ) {
        val project = projectRoot.toAbsolutePath().normalize()
        val site = siteRoot.toAbsolutePath().normalize()
        val inventory = inventoryFile.toAbsolutePath().normalize()
        ShowcasePaths.requireDirectory(project, "Pages source repository")
        ShowcasePaths.requireDirectory(site, "staged Pages root")
        require(inventory.startsWith(site)) { "Pages URL inventory must remain inside the staged site." }
        ShowcasePaths.requireRegularFile(inventory, "Pages URL inventory")
        val expected = PagesPublicUrlInventory.expected(project, site)
        val actual = Files.readAllLines(inventory, StandardCharsets.UTF_8)
        require(actual == expected) { "Pages URL inventory differs from staged source evidence." }
        val documents = linkedSetOf<Path>()
        actual.forEach { publicPath ->
            val relative = PagesPublicUrlInventory.stagedRelativePath(publicPath)
            val staged = site.resolve(relative).normalize()
            require(staged.startsWith(site)) { "Pages target escapes the staged site: $publicPath" }
            ShowcasePaths.requireSafeSegments(staged, "inventoried Pages target")
            require(Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
                "Inventoried Pages target has no staged file: $publicPath -> $relative"
            }
            if (relative.endsWith(HTML_SUFFIX, ignoreCase = true) && isSnapshotTarget(site, staged).not()) documents.add(staged)
        }
        checkSourceReceipt(site)
        documents.forEach { document -> checkHtmlDocument(site, document) }
    }

    private fun checkSourceReceipt(siteRoot: Path) {
        val revisionFile = siteRoot.resolve(SOURCE_REVISION_FILE)
        val receiptFile = siteRoot.resolve(SOURCE_RECEIPT_FILE)
        val revisionText = Files.readString(revisionFile, StandardCharsets.UTF_8)
        val revisionMatch = SOURCE_REVISION.matchEntire(revisionText)
        require(revisionMatch != null) { "Pages source revision is not canonical: $revisionFile" }
        val receiptText = Files.readString(receiptFile, StandardCharsets.UTF_8)
        val receiptMatch = SOURCE_RECEIPT.matchEntire(receiptText)
        require(receiptMatch != null) { "Pages source receipt is not canonical: $receiptFile" }
        val revision = requireNotNull(revisionMatch.groups[REVISION_GROUP]).value
        val receiptRevision = requireNotNull(receiptMatch.groups[REVISION_GROUP]).value
        require(receiptRevision == revision) {
            "Pages source receipt revision differs from source-revision.txt."
        }
    }

    private fun checkHtmlDocument(
        siteRoot: Path,
        document: Path,
    ) {
        htmlTargets(document).forEach { uri ->
            val file = currentTarget(siteRoot, document, uri) ?: return@forEach
            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                "Staged Pages link or asset target is missing: $document -> $uri"
            }
            val fragment = uri.fragment
            val legacyMarkdown = isSnapshotTarget(siteRoot, file) && file.toString().endsWith(MARKDOWN_SUFFIX, ignoreCase = true)
            if (fragment != null && fragment.isNotEmpty() && legacyMarkdown.not()) {
                require(fragment in documentAnchors(file)) { "Staged Pages anchor is missing: $document -> $uri" }
            }
        }
    }

    private fun currentTarget(
        siteRoot: Path,
        document: Path,
        uri: URI,
    ): Path? {
        val resolved = resolveStagedTarget(siteRoot, document, uri) ?: return null
        require(resolved.startsWith(siteRoot)) { "Staged Pages link escapes the deployed site: $document -> $uri" }
        ShowcasePaths.requireSafeSegments(resolved, "staged Pages link target")
        val file =
            if (uri.path.orEmpty().endsWith('/') || Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
                resolved.resolve(INDEX_FILE).normalize()
            } else {
                resolved
            }
        require(file.startsWith(siteRoot)) { "Staged Pages directory link escapes the deployed site: $document -> $uri" }
        ShowcasePaths.requireSafeSegments(file, "staged Pages link target")
        require(isSnapshotTarget(siteRoot, file) || file.toString().endsWith(MARKDOWN_SUFFIX, ignoreCase = true).not()) {
            "Reader Markdown must link to GitHub, not a Pages document: $document -> $uri"
        }
        return file
    }

    private fun isSnapshotTarget(
        siteRoot: Path,
        target: Path,
    ): Boolean = target.startsWith(siteRoot.resolve(RELEASES_DIRECTORY)) && 2 < siteRoot.relativize(target).nameCount

    private fun resolveStagedTarget(
        siteRoot: Path,
        document: Path,
        uri: URI,
    ): Path? {
        if (uri.isAbsolute || uri.rawAuthority != null) {
            if (
                (uri.scheme != null && DocumentationUriScheme.decode(uri.scheme) != DocumentationUriScheme.HTTPS) ||
                uri.host.equals(PAGES_HOST, ignoreCase = true).not()
            ) {
                return null
            }
            val publicPath = uri.path ?: return null
            if (publicPath == PAGES_BASE_PATH.removeSuffix("/")) return siteRoot
            require(publicPath.startsWith(PAGES_BASE_PATH)) { "Staged Pages URL lies outside the deployed site: $uri" }
            return siteRoot.resolve(publicPath.removePrefix(PAGES_BASE_PATH)).normalize()
        }
        val path = uri.path.orEmpty()
        if (path.isEmpty()) return document
        return if (path.startsWith('/')) {
            if (path == PAGES_BASE_PATH.removeSuffix("/")) return siteRoot
            require(path.startsWith(PAGES_BASE_PATH)) { "Root-relative staged Pages link lies outside the deployed site: $uri" }
            siteRoot.resolve(path.removePrefix(PAGES_BASE_PATH)).normalize()
        } else {
            document.parent.resolve(path).normalize()
        }
    }

    private fun documentAnchors(document: Path): Set<String> {
        require(document.toString().endsWith(HTML_SUFFIX, ignoreCase = true)) {
            "A staged Pages fragment must target HTML: $document"
        }
        return HTML_TAG
            .findAll(Files.readString(document, StandardCharsets.UTF_8))
            .flatMap { tag -> HTML_ANCHOR.findAll(tag.value) }
            .map { match -> decodeHtmlAttribute(match.groupValues[2]) }
            .toSet()
    }

    private fun htmlTargets(document: Path): Sequence<URI> =
        HTML_TAG
            .findAll(Files.readString(document, StandardCharsets.UTF_8))
            .flatMap { tag -> HTML_TARGET.findAll(tag.value) }
            .map { match -> decodeHtmlAttribute(match.groupValues[2]).trim().replace('\\', '/') }
            .filter { target -> target.isNotEmpty() }
            .map { target ->
                runCatching { URI(target) }.getOrElse { failure ->
                    throw IllegalArgumentException("Malformed staged Pages link: $document -> $target", failure)
                }
            }

    private fun decodeHtmlAttribute(value: String): String =
        value
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private val HTML_TARGET = Regex("""\s(?:src|href)\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
    private val HTML_ANCHOR = Regex("""\s(?:id|name)\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("<[A-Za-z][^>]*>")
    private const val HTML_SUFFIX = ".html"
    private const val MARKDOWN_SUFFIX = ".md"
    private const val RELEASES_DIRECTORY = "releases"
    private const val INDEX_FILE = "index.html"
    private const val PAGES_HOST = "gh.s7a.dev"
    private const val PAGES_BASE_PATH = "/strata/"
    private const val SOURCE_RECEIPT_FILE = "source-receipt.json"
    private const val SOURCE_REVISION_FILE = "source-revision.txt"
    private const val SOURCE_REVISION_PATTERN = "master|v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?|[0-9a-f]{40}"
    private val SOURCE_RECEIPT =
        Regex("\\{\"commit\":\"[0-9a-f]{40}\",\"revision\":\"(?<$REVISION_GROUP>$SOURCE_REVISION_PATTERN)\"}\\n")
    private val SOURCE_REVISION = Regex("(?<$REVISION_GROUP>$SOURCE_REVISION_PATTERN)\\n")
    private const val REVISION_GROUP = "revision"
}
