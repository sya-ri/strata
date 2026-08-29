package dev.s7a.strata.integration.docs

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Produces the deterministic public URL inventory verified before and after Pages deployment.
 */
internal object PagesPublicUrlInventory {
    /**
     * Writes one relative-URL-per-line inventory from repository links and staged Dokka entrypoints.
     *
     * @param args repository root, staged Pages root, and output file.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 3) { "Pages URL inventory requires repository, staged-site, and output paths." }
        write(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]))
    }

    /**
     * Writes the exact deterministic inventory with LF encoding and one terminal newline.
     *
     * @param projectRoot trusted repository root.
     * @param siteRoot complete staged Pages root.
     * @param outputFile inventory destination inside [siteRoot].
     */
    internal fun write(
        projectRoot: Path,
        siteRoot: Path,
        outputFile: Path,
    ) {
        val site = siteRoot.toAbsolutePath().normalize()
        val output = outputFile.toAbsolutePath().normalize()
        require(output.startsWith(site)) { "Pages URL inventory must remain inside the staged site." }
        ShowcasePaths.requireSafeSegments(output, "Pages URL inventory")
        val content = expected(projectRoot, site).joinToString(separator = "\n", postfix = "\n")
        Files.writeString(
            output,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    /**
     * Computes required public relative URLs from checked source literals and the Dokka root's HTML links.
     *
     * Reads current output synchronously and includes directly advertised snapshot files without traversing their contents.
     * Rejects leftover reader-guide output and links that advertise Markdown as Pages documents.
     *
     * @param projectRoot trusted repository root.
     * @param siteRoot complete staged Pages root.
     * @return sorted unique paths beginning with `/`.
     */
    internal fun expected(
        projectRoot: Path,
        siteRoot: Path,
    ): List<String> {
        val project = projectRoot.toAbsolutePath().normalize()
        val site = siteRoot.toAbsolutePath().normalize()
        ShowcasePaths.requireDirectory(project, "Pages source repository")
        ShowcasePaths.requireDirectory(site, "staged Pages root")
        require(Files.exists(site.resolve(GUIDE_DIRECTORY), LinkOption.NOFOLLOW_LINKS).not()) {
            "Legacy Pages guide output remains; clean generated output and regenerate the Dokka API site."
        }
        return buildSet {
            add("/")
            add("/index.html")
            add("/source-receipt.json")
            add("/source-revision.txt")
            addAll(hardCodedPublicPaths(project))
            addAll(PagesStagingChecker.rootEntrypointPaths(site))
        }.sorted()
    }

    /**
     * Maps one public relative URL to its staged file path.
     *
     * @param publicPath path beginning with `/`, without a query or fragment.
     * @return contained relative file path, with directory URLs mapped to `index.html`.
     */
    internal fun stagedRelativePath(publicPath: String): String {
        require(publicPath.startsWith('/')) { "Pages public path must begin with '/': $publicPath" }
        require(publicPath.contains('?').not() && publicPath.contains('#').not()) {
            "Pages public path must not contain a query or fragment: $publicPath"
        }
        val relative = publicPath.removePrefix("/")
        val normalized = Path.of(relative).normalize()
        val snapshotTarget = normalized.startsWith(RELEASES_DIRECTORY) && 2 < normalized.nameCount
        require(snapshotTarget || publicPath.endsWith(MARKDOWN_SUFFIX, ignoreCase = true).not()) {
            "Reader Markdown must link to GitHub, not a Pages document: $publicPath"
        }
        return if (relative.isEmpty() || relative.endsWith('/')) "$relative$INDEX_FILE" else relative
    }

    private fun hardCodedPublicPaths(projectRoot: Path): Set<String> =
        sourceFiles(projectRoot)
            .flatMap { path ->
                PAGES_URL
                    .findAll(Files.readString(path, StandardCharsets.UTF_8))
                    .map { match -> publicPath(match.value) }
            }.toSet()

    private fun sourceFiles(projectRoot: Path): List<Path> {
        val files = ArrayList<Path>()
        Files.walkFileTree(
            projectRoot,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    val excluded =
                        directory != projectRoot &&
                            (directory.fileName.toString() in EXCLUDED_DIRECTORIES || Files.isSymbolicLink(directory))
                    return if (excluded) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    val extension = file.fileName.toString().substringAfterLast('.', missingDelimiterValue = "")
                    if (attributes.isRegularFile && Files.isSymbolicLink(file).not() && extension in CHECKED_EXTENSIONS) {
                        files.add(file)
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return files.sorted()
    }

    private fun publicPath(target: String): String {
        val uri = URI(target)
        require(
            DocumentationUriScheme.decode(uri.scheme) == DocumentationUriScheme.HTTPS &&
                uri.host.equals(PAGES_HOST, ignoreCase = true),
        ) {
            "Unexpected Pages target: $target"
        }
        val relative = uri.path.removePrefix(PAGES_BASE_PATH)
        require(relative != uri.path) { "Pages target lies outside the Strata site: $target" }
        val publicPath = "/$relative"
        stagedRelativePath(publicPath)
        return publicPath
    }

    private val CHECKED_EXTENSIONS =
        setOf("gradle", "html", "java", "json", "kt", "kts", "md", "properties", "toml", "txt", "xml", "yaml", "yml")
    private val EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", "build", "out")
    private val PAGES_URL =
        Regex(
            """https://gh\.s7a\.dev/strata/(?:[A-Za-z0-9._~%+-]+/)*[A-Za-z0-9._~%+-]*(?:#[A-Za-z0-9._~-]+)?(?=[\s"'`),;<>\]}]|$)""",
        )
    private const val PAGES_HOST = "gh.s7a.dev"
    private const val PAGES_BASE_PATH = "/strata/"
    private const val GUIDE_DIRECTORY = "guide"
    private const val RELEASES_DIRECTORY = "releases"
    private const val MARKDOWN_SUFFIX = ".md"
    private const val INDEX_FILE = "index.html"
}
