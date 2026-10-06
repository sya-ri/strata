# Release publication

Use **Publish release** on `master` to prepare, publish, or verify a signed release tag.
The tagged build owns the distribution inventory; no current/predecessor version list is maintained in the controller.
[CI](ci.md) owns routine verification, and [documentation maintenance](documentation.md) owns generated reader content.

## Prepare and publish

Finish the release notes under `docs/releases`, update the product version, and create a signed annotated tag pointing at the product commit.
The tag must be verified by GitHub, match the build version, and belong to the controller's history.
The wildcard release-tag ruleset prevents updates and deletion without bypass actors.
The workflow checks the tag and ruleset again before each publication job.

Dispatch `publish-release.yml` with `operation=release`, `tag=vX.Y.Z`, the full `source_commit`, and `confirmation=release vX.Y.Z`.
Leave `prepared_run_id` empty only for the first preparation.
The protected `release` environment controls signing and publication credentials.

Preparation runs the full quality suite, Kover, and Qodana against the selected tag, builds and signs the artifacts, and checks standalone Maven fixtures.
The authoring fixture loads `strata-detekt-rules` by its coordinate and verifies every [guide example](../guides/authoring-checks.md): corrected code has no findings, while incorrect code triggers exactly one finding per rule.
Before browser verification, it installs Chromium, Firefox, WebKit, and their Linux system dependencies using the tagged product's pinned Playwright CLI.
The controller selects Loom's remapped Fabric sources before signing and consumer verification when the tagged build also registers the unremapped development sources.
It applies the tagged runtime's declared Loom plugin before the root build configures that publication, so Maven and Gradle sources metadata refer to the same remapped JAR.
It removes only that exact duplicate pair; an unexpected sources inventory fails preparation.
It saves the generated Maven inventory, original artifacts and signatures, destination manifests, release notes, public signing key, and a file-by-file SHA-256 inventory in `release-prepared.tar.gz`.
The manifest binds these bytes to the signed tag object, product commit, controller commit, workflow run, and attempt.
`prepared-<tag>-<attempt>` retains this archive in Actions for 90 days.
Preparation succeeds before any publication job consumes it.

Maven Central, GitHub, Modrinth, CurseForge, and Hangar run as independent parallel jobs after preparation.
Only selected destinations run, so one service's outage does not block or restart the others.
An exact existing publication is reused, an absent publication can be added, and a partial or conflicting publication stops that destination.
Publication and verification run from the saved bundle using isolated controller tools; they do not configure, build, test, or sign the product again.
GitHub receives the original preparation archive and signatures from the saved Maven inventory before its release becomes public and immutable.
After creating a draft, the controller retries its read-only lookup briefly; if it stays invisible, publication stops and can resume from the same preparation.
It does not fetch signatures from Maven Central or wait for Central publication.

## Retry and verification

For a partial failure, rerun the failed jobs or dispatch `operation=release` with the original `prepared_run_id` and only the failed destinations enabled.
The preparation job must have succeeded, but the original run may have failed later.
`operation=verify` requires that same `prepared_run_id` and confirmation `verify vX.Y.Z`; it performs remote reads and artifact comparisons without publication.

| Input | Enabled behavior |
| --- | --- |
| `maven_central` | Publish an absent Maven release or verify its exact files and signatures. Disabled destinations perform no remote checks. |
| `github_release` | Create/resume the draft, check every asset, and publish it; verification requires a public exact release. |
| `modrinth` | Add missing listed Fabric versions and verify their files independently of project review or description changes. |
| `curseforge` | Add missing Fabric files using trusted upload receipts; optional read access also verifies public downloads. |
| `hangar` | Add or reuse one Paper/Velocity version and verify its downloads. |

Each destination has an independent receipt and can be selected independently; at least one destination must be enabled.
A retry downloads the original Actions artifact or, after expiry, the matching immutable GitHub Release archive.
Both paths validate its producer, source identity, complete inventory, and hashes.
Missing archives, mismatched tag objects, or unsuccessful preparation stop the run instead of silently rebuilding a supposedly identical release.
Published Maven downloads are compared with the saved inventory and their original detached signatures.
Portal verification also requires and checks the signature checksum sidecars when the saved preparation contains them; historical base-only inventories retain their original verification contract.

## Credentials and project setup

| Secret | Scope | Purpose |
| --- | --- | --- |
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | Protected `release` environment | Publisher Portal publication and verification |
| `SIGNING_KEY`, `SIGNING_PASSWORD` | Protected `release` environment | First preparation only |
| `MODRINTH_TOKEN` | Protected `release` environment | Modrinth version publication and authenticated reconciliation |
| `CURSEFORGE_TOKEN` | Protected `release` environment | CurseForge uploads |
| `CURSEFORGE_API_KEY` | Optional repository secret | CurseForge remote reconciliation, approval monitoring, and download verification |
| `HANGAR_API_TOKEN` | Protected `release` environment | Hangar permission checks, uploads, and private version observation |

Project identities live in `release/modrinth-project.json`, `release/curseforge-project.json`, and `release/hangar-project.json`.
Keep an unconfigured destination disabled.
Hangar requires a project-scoped key with `view_public_info` and `create_version`; the official Gradle publisher uploads the saved Paper and Velocity JARs together.
Folia uses the Paper artifact.

CurseForge's upload token and read API key are separate credentials.
Do not shadow the repository read key with another environment value.
Without the read key, accepted file IDs still prevent duplicate submissions, but public status and download verification are explicitly unchecked.
A rejected configured read key is an error, not an instruction to skip verification.

Before each CurseForge write, the client persists an attempting record and then records the accepted file ID.
The attempt-qualified receipt is uploaded even on failure.
Later runs validate historical receipts and their producer/digest before continuing; an uncertain write or missing prior receipt forbids another upload until reconciled.
The workflow run title and CurseForge stage-step name are part of that history lookup contract.
Upload acceptance does not imply moderation approval.

## Approval monitoring and recovery

`publication-status.yml` reads successful publication receipts on completion and hourly.
It checks anonymous Modrinth version visibility and Hangar visibility, plus CurseForge status when the optional read key exists, then requests one protected verification run using the original preparation.
It neither retries uploads nor changes project descriptions or moderation state.
A failed verification requires inspection and an explicit retry.
The run summary and independent destination receipts distinguish accepted uploads, pending moderation, verified downloads, and unchecked CurseForge status.

Skill generation and project descriptions are documentation work, independent of binary publication.
Their checks run through documentation CI; update project pages from the generated files in `docs/publication` using the corresponding service's project tools.
A project page or Pages failure does not invalidate or rebuild published binaries.

## Pages artifacts and deployment

The Dokka versioning plugin uses the same version catalog entry as Dokka.
The root site is development documentation (`dev`), and release documentation lives under `older/<version>/`.
The version menu lists development first and releases in descending numeric order.
Reader Markdown stays on GitHub; Dokka and Web demos form the Pages site.

`pages.yml` runs for documentation inputs on `master`, or with `release_tag` to add a newly published release.
The release workflow dispatches that addition separately from binary publication.
The Pages job restores rendered history from `gh-pages`, generates a missing new release once, and passes existing release directories to the plugin without rebuilding their tags.
The first migration imports historical HTML from a successful Pages artifact and adds each version's `version.json` metadata.
If neither saved history nor an eligible migration artifact exists, migration stops and requires the existing rendered site.

The same generated site is validated, committed to `gh-pages`, uploaded, and deployed through GitHub Pages.
Source receipts retain each version's tag and commit; source links are checked against their own version rather than the development revision.
Actions retains deployment artifacts for 30 days, while `gh-pages` stores the reusable rendered history.
The new `older/` layout has no redirects for former URLs.
