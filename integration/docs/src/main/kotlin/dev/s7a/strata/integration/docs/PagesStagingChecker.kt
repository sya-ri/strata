package dev.s7a.strata.integration.docs

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale

/**
 * Verifies hard-coded Strata Pages URLs and the complete staged reader-guide link topology.
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
     * Resolves every inventoried Pages target and every local link, image, and anchor in the staged guide tree without following links.
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
        require(Files.isRegularFile(inventory, LinkOption.NOFOLLOW_LINKS)) { "Pages URL inventory is missing: $inventory" }
        require(Files.isSymbolicLink(inventory).not()) { "Pages URL inventory must not be symbolic." }
        val expected = PagesPublicUrlInventory.expected(project, site)
        val actual = Files.readAllLines(inventory, StandardCharsets.UTF_8)
        require(actual == expected) { "Pages URL inventory differs from staged source evidence." }
        actual.forEach { publicPath ->
            val relative = PagesPublicUrlInventory.stagedRelativePath(publicPath)
            val staged = site.resolve(relative).normalize()
            require(staged.startsWith(site)) { "Pages target escapes the staged site: $publicPath" }
            require(Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
                "Inventoried Pages target has no staged file: $publicPath -> $relative"
            }
            require(Files.isSymbolicLink(staged).not()) { "Inventoried Pages target is staged as a symbolic file: $publicPath" }
        }
        checkSourceReceipt(site)
        checkGuideTopology(site)
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
        require(receiptMatch.groupValues[2] == revisionMatch.groupValues[1]) {
            "Pages source receipt revision differs from source-revision.txt."
        }
    }

    private fun checkGuideTopology(siteRoot: Path) {
        val guideRoot = siteRoot.resolve(GUIDE_DIRECTORY).normalize()
        ShowcasePaths.requireDirectory(guideRoot, "staged Pages guide root")
        val documents =
            Files.walk(guideRoot).use { stream ->
                stream
                    .filter { path ->
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                            path.fileName
                                .toString()
                                .substringAfterLast('.', missingDelimiterValue = "")
                                .lowercase() in DOCUMENT_EXTENSIONS
                    }.peek { path ->
                        ShowcasePaths.requireSafeSegments(path, "staged Pages guide document")
                    }.sorted()
                    .toList()
            }
        require(documents.isNotEmpty()) { "Staged Pages guide contains no Markdown or HTML documents." }
        documents.forEach { document -> checkGuideDocument(siteRoot, document) }
    }

    private fun checkGuideDocument(
        siteRoot: Path,
        document: Path,
    ) {
        val prose = removeFencedCode(Files.readString(document, StandardCharsets.UTF_8))
        val markdownTargets =
            MARKDOWN_TARGET.findAll(prose).map { match ->
                StagedTarget(match.groupValues[2], image = match.groupValues[1].isNotEmpty())
            }
        val htmlTargets =
            HTML_TARGET.findAll(prose).map { match ->
                StagedTarget(match.groupValues[3], image = match.groupValues[1].equals("src", ignoreCase = true))
            }
        (markdownTargets + htmlTargets).forEach { target -> checkGuideTarget(siteRoot, document, target) }
    }

    private fun checkGuideTarget(
        siteRoot: Path,
        document: Path,
        stagedTarget: StagedTarget,
    ) {
        val target = targetToken(stagedTarget.raw)
        if (target.isEmpty()) return
        val uri =
            runCatching { URI(target) }.getOrElse { failure ->
                throw IllegalArgumentException("Malformed staged Pages link: $document -> $target", failure)
            }
        val resolved = resolveStagedTarget(siteRoot, document, uri) ?: return
        require(resolved.startsWith(siteRoot)) { "Staged Pages link escapes the deployed site: $document -> $target" }
        ShowcasePaths.requireSafeSegments(resolved, "staged Pages link target")
        val file =
            if (target.substringBefore('#').substringBefore('?').endsWith('/') || Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
                resolved.resolve(INDEX_FILE).normalize()
            } else {
                resolved
            }
        require(file.startsWith(siteRoot)) { "Staged Pages directory link escapes the deployed site: $document -> $target" }
        ShowcasePaths.requireSafeSegments(file, "staged Pages link target")
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            val kind = if (stagedTarget.image) "image" else "link"
            "Staged Pages $kind target is missing: $document -> $target"
        }
        if (stagedTarget.image) {
            val extension =
                file.fileName
                    .toString()
                    .substringAfterLast('.', missingDelimiterValue = "")
                    .lowercase()
            require(extension in IMAGE_EXTENSIONS) { "Staged Pages image has an unsupported file type: $document -> $target" }
        }
        val fragment = uri.fragment
        if (fragment != null && fragment.isNotEmpty()) {
            val anchors = documentAnchors(file)
            require(fragment in anchors) { "Staged Pages anchor is missing: $document -> $target" }
        }
    }

    private fun resolveStagedTarget(
        siteRoot: Path,
        document: Path,
        uri: URI,
    ): Path? {
        if (uri.isAbsolute || uri.scheme != null) {
            if (
                DocumentationUriScheme.decode(uri.scheme) != DocumentationUriScheme.HTTPS ||
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
        val extension =
            document.fileName
                .toString()
                .substringAfterLast('.', missingDelimiterValue = "")
                .lowercase()
        require(extension in DOCUMENT_EXTENSIONS) { "A staged Pages fragment must target Markdown or HTML: $document" }
        val prose = removeFencedCode(Files.readString(document, StandardCharsets.UTF_8))
        return buildSet {
            HTML_ANCHOR.findAll(prose).forEach { match -> add(match.groupValues[2]) }
            if (extension == MARKDOWN_EXTENSION) {
                val duplicates = mutableMapOf<String, Int>()
                MARKDOWN_HEADING.findAll(prose).forEach { match ->
                    val base = markdownAnchor(match.groupValues[1])
                    if (base.isNotEmpty()) {
                        val duplicate = duplicates.getOrDefault(base, 0)
                        add(if (duplicate == 0) base else "$base-$duplicate")
                        duplicates[base] = duplicate + 1
                    }
                }
            }
        }
    }

    private fun markdownAnchor(heading: String): String =
        heading
            .replace(HTML_TAG, "")
            .replace(MARKDOWN_INLINE_LINK) { match -> match.groupValues[1] }
            .replace(Regex("[`*_~]"), "")
            .trim()
            .removeSuffix("#")
            .trim()
            .lowercase(Locale.ROOT)
            .filter { character -> character.isLetterOrDigit() || character == ' ' || character == '-' || character == '_' }
            .trim()
            .replace(Regex("\\s+"), "-")

    private fun targetToken(rawTarget: String): String {
        val target = rawTarget.trim()
        if (target.startsWith('<')) {
            val closing = target.indexOf('>')
            require(0 < closing) { "Malformed angle-bracketed staged Pages target: $rawTarget" }
            return target.substring(1, closing)
        }
        return target.split(Regex("\\s+"), limit = 2).first()
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

    private data class StagedTarget(
        val raw: String,
        val image: Boolean,
    )

    private val MARKDOWN_TARGET = Regex("(!?)\\[[^]]*]\\(([^)]+)\\)")
    private val HTML_TARGET = Regex("""\b(src|href)\s*=\s*(["'])(.*?)\2""", RegexOption.IGNORE_CASE)
    private val HTML_ANCHOR = Regex("""\b(id|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val MARKDOWN_HEADING = Regex("(?m)^ {0,3}#{1,6}\\s+(.+?)\\s*$")
    private val MARKDOWN_INLINE_LINK = Regex("\\[([^]]+)]\\([^)]+\\)")
    private val HTML_TAG = Regex("<[^>]+>")
    private val DOCUMENT_EXTENSIONS = setOf("html", "md")
    private val IMAGE_EXTENSIONS = setOf("gif", "jpeg", "jpg", "png", "svg", "webp")
    private const val GUIDE_DIRECTORY = "guide"
    private const val MARKDOWN_EXTENSION = "md"
    private const val INDEX_FILE = "index.html"
    private const val PAGES_HOST = "gh.s7a.dev"
    private const val PAGES_BASE_PATH = "/strata/"
    private const val SOURCE_RECEIPT_FILE = "source-receipt.json"
    private const val SOURCE_REVISION_FILE = "source-revision.txt"
    private const val SOURCE_REVISION_PATTERN = "(?:master|v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?|[0-9a-f]{40})"
    private val SOURCE_RECEIPT = Regex("\\{\"commit\":\"([0-9a-f]{40})\",\"revision\":\"($SOURCE_REVISION_PATTERN)\"}\\n")
    private val SOURCE_REVISION = Regex("($SOURCE_REVISION_PATTERN)\\n")
}
