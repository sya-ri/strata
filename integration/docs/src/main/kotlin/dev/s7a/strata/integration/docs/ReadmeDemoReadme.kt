package dev.s7a.strata.integration.docs

/**
 * Strict replacement of the README demo's unique anchored region without touching other generated sections.
 * The caller owns reading and writing; these pure operations return new strings and reject malformed anchors.
 */
internal object ReadmeDemoReadme {
    private const val BEGIN = "<!-- strata-readme-demo:start -->"
    private const val END = "<!-- strata-readme-demo:end -->"

    /**
     * Fixed public entry point linking the animation, static walkthrough, and complete compiled final example.
     */
    val region: String =
        """
        [![Strata: compose player rows, align text, and link a scrollbar](docs/readme-demo/demo.gif)](docs/readme-demo/README.md)

        [Example](${ReadmeDemoSource.DIRECTORY}/ScrollPlayersExample.kt) · [Still frames](docs/readme-demo/README.md)
        """.trimIndent() + "\n"

    /**
     * Replaces the owned interior, preserving the exact prefix and suffix, including line endings.
     */
    fun replace(source: String): String {
        require(source.split(BEGIN).size == 2 && source.split(END).size == 2) { "README demo anchors must be unique." }
        val start = source.indexOf(BEGIN)
        val end = source.indexOf(END)
        require(start < end) { "README demo anchors must be ordered." }
        require((start == 0 || source[start - 1] == '\n') && source[start + BEGIN.length] == '\n') { "README demo begin marker must be standalone." }
        require(source[end - 1] == '\n' && (end + END.length == source.length || source[end + END.length] == '\n')) { "README demo end marker must be standalone." }
        return source.take(start + BEGIN.length) + "\n" + region + source.substring(end)
    }
}
