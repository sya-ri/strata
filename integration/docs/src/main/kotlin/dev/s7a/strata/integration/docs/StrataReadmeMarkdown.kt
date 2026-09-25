package dev.s7a.strata.integration.docs

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates versioned installation instructions and the compiled README opening example.
 */
internal object StrataReadmeMarkdown {
    /**
     * Replaces the installation and compiled opening example while preserving authored README content.
     */
    internal fun render(
        projectRoot: Path,
        openExample: String,
        releaseVersion: String,
    ): String {
        val readmePath = projectRoot.resolve("README.md")
        val readme = Files.readString(readmePath, StandardCharsets.UTF_8)
        val withInstallation =
            replaceGeneratedRegion(
                readme,
                "<!-- strata-installation:start -->",
                "<!-- strata-installation:end -->",
                "${fabricInstallation(releaseVersion)}\n\n${serverInstallation(releaseVersion)}",
                "installation",
            )
        val openingExample =
            """```kotlin
$openExample
```"""
        return replaceGeneratedRegion(
            withInstallation,
            "<!-- strata-api-open-example:start -->",
            "<!-- strata-api-open-example:end -->",
            openingExample,
            "Strata API opening-example",
        )
    }

    private fun fabricInstallation(releaseVersion: String): String =
        """### Fabric client

Players need the Strata Fabric runtime and Fabric Language Kotlin for both client-defined and server-driven UIs.
Client Mod UI source needs only `strata-api` on its compile classpath.
Install exactly one version-matched runtime as a separate client Fabric Mod together with Fabric Language Kotlin; do not bundle multiple versioned Strata runtimes.

```kotlin
dependencies {
    compileOnly("dev.s7a.strata:strata-api:$releaseVersion")
    modRuntimeOnly("dev.s7a.strata:strata-runtime-minecraft-fabric-<minecraft-version>:$releaseVersion")
    modRuntimeOnly("net.fabricmc:fabric-language-kotlin:<compatible-version>")
}
```

The version-matched runtimes are also available from [Modrinth](https://modrinth.com/mod/strata-ui).
Declare it as a required dependency in the consuming Mod so `UiDefinition.open()` always has a presenter in production:

```json
{
  "depends": {
    "strata": ">=$releaseVersion"
  }
}
```"""

    private fun serverInstallation(releaseVersion: String): String =
        """### Paper, Folia, and Velocity

Install the appropriate Strata plugin JAR with the `plugin` classifier in the server or proxy's `plugins` directory.
Keep the client and server/proxy on the same Strata release.
The matching Fabric client Mod is required for every player using the UI.

| Platform | Installed Strata plugin | Consumer dependency (`compileOnly`) |
| --- | --- | --- |
| [Paper / Folia](docs/guides/paper.md) | `strata-runtime-paper` | `dev.s7a.strata:strata-paper-api:$releaseVersion` |
| [Velocity](docs/guides/velocity.md) | `strata-runtime-velocity` | `dev.s7a.strata:strata-velocity-api:$releaseVersion` |

Compile against the platform's own API as well, and do not bundle Strata into your plugin.
Paper consumers declare `depend: [Strata]` in `plugin.yml`; Folia consumers also declare `folia-supported: true`.
Velocity consumers declare a required dependency on plugin ID `strata`.
A Velocity-owned UI needs the client Mod and proxy plugin; install Strata on a Paper backend only when that backend also owns UIs.

The [Paper / Folia guide](docs/guides/paper.md) and [Velocity guide](docs/guides/velocity.md) include compiled examples and the state, threading, and lifecycle contracts."""

    private fun replaceGeneratedRegion(
        document: String,
        begin: String,
        end: String,
        content: String,
        label: String,
    ): String {
        require(document.split(begin).size == 2 && document.split(end).size == 2) {
            "README must contain exactly one $label marker pair."
        }
        val beginIndex = document.indexOf(begin)
        val endIndex = document.indexOf(end)
        require(beginIndex < endIndex) { "README $label markers are out of order." }
        val region = "$begin\n$content\n$end"
        return document.substring(0, beginIndex) + region + document.substring(endIndex + end.length)
    }
}
