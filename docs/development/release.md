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

Unsigned full-product verification and Qodana start independently after signed-source admission.
Verification retains the existing complete release command, including Kover after JVM tests; it does not use PR changed-path selection or partition the product task graph.
Its task observer disables up-to-date and build-cache reuse for tasks implementing Gradle's `VerificationTask` contract while retaining reusable dependency and compilation inputs.
Only **Prepare release** accesses the protected signing environment, after both verification and all required analysis succeed.
Its gate checks the actual product and controller checkouts, signed tag object, producer run and attempt, analysis report digests, and the complete verification artifact's downloaded digest and receipt inventory.
Missing, failed, cancelled, skipped, wrong-revision, or altered required evidence prevents signing and preparation reuse.
Successful verification from an earlier attempt of the same run remains attributable when only failed jobs are rerun.

Preparation builds and signs the artifacts and checks standalone Maven fixtures in a new isolated Maven repository, including the nested consumer builds.
The authoring fixture loads `strata-detekt-rules` by its coordinate and verifies every [guide example](../guides/authoring-checks.md): corrected code has no findings, while incorrect code triggers exactly one finding per rule.
Before browser verification, it installs Chromium, Firefox, WebKit, and their Linux system dependencies using the tagged product's pinned Playwright CLI.
The controller selects Loom's remapped Fabric sources before signing and consumer verification when the tagged build also registers the unremapped development sources.
It applies the tagged runtime's declared Loom plugin before the root build configures that publication, so Maven and Gradle sources metadata refer to the same remapped JAR.
It removes only that exact duplicate pair; an unexpected sources inventory fails preparation.
It saves the generated Maven inventory, original artifacts and signatures, destination manifests, release notes, public signing key, and a file-by-file SHA-256 inventory in `release-prepared.tar.gz`.
The manifest binds these bytes and the quality-gate receipt to the signed tag object, product commit, controller commit, workflow run, and attempt.
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
Final Central verification writes the canonical base files and detached signatures from the same successful inspection instead of downloading them again for evidence assembly.
Every base-byte/checksum/signature requirement and bounded read/propagation retry remains enabled; failed or cancelled verification removes its partial staging directory.
This reduces duplicate read requests in the local complete-inventory fixture; it does not measure Sonatype processing or public propagation time.

Unsigned verification retains sanitized phase consoles, identities and timings, available test XML/HTML, browser/native parity output, loaded-client logs, and crash reports in a separate attempt-qualified artifact, including after failure.
Phases that never reach screenshot capture are recorded without requiring nonexistent screenshots.
Signing diagnostics are retained separately from both unsigned evidence and immutable publication inputs.
The result summary validates the reusable archive before offering its original producer run/attempt and digest with exact destination-only retry inputs.
Unexpected skips and cancellation fail result aggregation.
Inspect the primary cause before retrying: compiler errors, assertions, quality findings and integrity conflicts require a correction, while a demonstrated transient infrastructure failure may justify rerunning only failed jobs and their dependents.
A rerun retains the original controller revision; a controller fix requires a new dispatch using the original successful `prepared_run_id`.
The signing gate also refuses another preparation when the same run already saved a signed archive; select its original producer for recovery.

## Non-publishing qualification

**Release rehearsal** accepts full immutable `source_commit` and `controller_commit` SHAs and a `serial`, `overlap` or `partitioned` topology.
The controller SHA must match the dispatched workflow revision.
It keeps the product's existing stable build version and generates rehearsal notes outside the source checkout, so it requires neither a new version nor a release tag.
Both topologies share production's actual full verification, analysis, signed packaging, standalone consumers and archive assembly.
`serial` waits for analysis before starting verification; `overlap` starts both independently.
Both use a separate signing runner, so this compares scheduling plans under one controller rather than reproducing historical release-run setup costs.

`partitioned` overlaps analysis with complete verification on isolated common, browser and versioned Minecraft runners, with at most eight verification shards running concurrently.
Its planner exports the selected product's original full-command graph and derives check owners and all supported Minecraft versions from that product.
Planning does not execute or satisfy verification.
Each shard runs real checks and retains its own reports, source identity, command, runner profile and producer attempt.
Aggregation verifies immutable artifact bytes and requires every planned shard plus coverage of every task in the original transitive graph before the existing signing gate can pass.
Successful shards from an earlier attempt of the same run remain attributable when only failed jobs are rerun; a changed product, controller or plan cannot reuse them.
The production route retains the full command while the partitioned topology is qualified against same-product task, test and evidence inventories and preparation timings.

Before the new workflow is registered on the default branch, the existing **JVM checks** manual entry can call it from the implementation branch:

```shell
gh workflow run jvm.yml --ref <controller-branch> -f rehearsal_source_commit=<full-product-sha> -f rehearsal_topology=serial
```

Use `rehearsal_topology=overlap` for the paired scheduling plan.
The wrapper forbids mixing rehearsal with paired performance inputs or selected-client inputs, and requires the selected rehearsal to succeed.
After registration, dispatch `release-rehearsal.yml` directly with the same product and controller SHAs and `topology`.
Follow [CI waiting](ci.md#waiting-for-ci) to check the outcome without repeatedly retrieving job details.

Rehearsals generate a disposable signing key and retain only explicitly named rehearsal archives and diagnostics.
They have no release environment, production signing/upload secrets, publication jobs, tag/release write permissions, Pages deployment, or publication-monitor dispatch.
Their manifest schema and scope are rejected by production prepared-release validation, independently of the existing producer-workflow check.
The local preparation fixture exercises real Gradle signatures, an initially empty Maven repository, nested consumer resolution, the task observer and production rejection without external publication.
Ordinary controller fixtures exercise failed/cancelled gates, late assertions, transient read failures, altered evidence, original-archive recovery and uncertain publication writes.

Use one unchanged product/task inventory, toolchains and runner class for matched trials, record both controller revisions, alternate order, and retain cold/warm reusable-input conditions without caching acceptance evidence.
The current rehearsal uses the ordinary read-only Gradle cache policy; its first samples do not establish controlled cold/warm performance parity.
Job/step timestamps, phase durations and aggregate runner-minutes by OS are retained with their revision and attempt identities.
Verification also records actual installed Java versions, runner image/architecture, logical processor count and hashes of the selected toolchain catalog and wrapper inputs.
The summary labels unavailable resource/dependency-ready observations and excludes its still-running result job and post-summary time.
Compare preparation-to-ready time, medians and ranges over at least three matched repetitions where practical; public visibility and Pages are separate milestones.
No elapsed-time saving, full-matrix equivalence or Central-read speedup is established by controller fixtures alone.
After independently checking the producer and ZIP digests, extract each successful verification artifact and run `python3 release/compare-verification.py <baseline-directory> <candidate-directory>`.
The retained comparison requires the unchanged product and full-command contract, validates each receipt inventory, and checks transitive tasks, diagnostic paths, JUnit case identities and multiplicities, failures, toolchains and runner classes.
Shared prerequisites may run in multiple shards, so extra fresh evidence is allowed; missing baseline evidence is an error.
Keep verification partitioning and read concurrency out of the production route until same-revision task/evidence parity and representative measurements qualify them.

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
