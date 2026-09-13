# Contributing to Strata

Use the [architecture](docs/development/architecture.md) to understand module boundaries and [AGENTS.md](AGENTS.md) for repository rules.
The [documentation index](docs/README.md) routes to guides and implementation contracts.

## Development setup

Run the checked-in Gradle wrapper from the repository root: `./gradlew`, or `.\gradlew.bat` in PowerShell.
Install the Java toolchains declared in the [version catalog](gradle/libs.versions.toml); automatic downloads are disabled.
See [build and verification](docs/development/build.md) for environment details and target-specific tasks.

## Making a change

- Keep platform code at runtime/integration boundaries and test behavior without Minecraft when possible.
- Before proposing a built-in component, check its focused responsibility and two independent uses, and assess whether composition from existing primitives is sufficient.
  Downstream application components have no such admission requirement.
- Follow the [adapter process](docs/development/minecraft-versions.md) for version support.
  Use Minecraft evidence tools or authoritative sources for version-sensitive facts.
- Update the canonical document when a contract changes.
  Match detail to the reader's task; comments should explain contracts or non-obvious decisions.
- Edit generated documentation through its source and run the tasks in [documentation maintenance](docs/development/documentation.md).

## Before review

Run affected module checks during development, then:

```shell
./gradlew check koverHtmlReport koverXmlReport -Pkover
```

Acceptance evidence must be recreated on the revision being reviewed.
For publication-code changes, also run `./gradlew publishToMavenLocal` and inspect the artifacts and metadata.
For documentation or public-skill changes, run:

```shell
./gradlew :integration:docs:checkStrataSkill :integration:docs:checkDocumentationLinks
```

## Commits and pull requests

Commit coherent changes with passing checks.
Follow recent commits: English imperative messages without a prefix.
Do not use `codex` in branch names or pull-request titles.
Describe the resulting behavior, relevant validation, and any remaining limits in the PR.
