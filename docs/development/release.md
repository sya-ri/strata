# Release publication

This is the maintainer contract for publishing and verifying a release.
Build and test the selected product source first using [build and verification](../development/build.md).
Release notes describe user-visible changes; executable manifests and controller metadata determine the exact publication inventory.

## Publication inputs

Each publishable JVM module has a Maven publication with source and Javadoc artifacts, MIT license metadata, SCM metadata, and an optional in-memory signing setup.
Provide `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, optional `signingInMemoryKeyId`, and optional `signingInMemoryKeyPassword` Gradle properties when publishing to Maven Central.
Environment variables use the `ORG_GRADLE_PROJECT_` prefix followed by the same property name.

## Controller and release identities

The historical `release.yml` and `release-v0.1.1.yml` workflows are sealed evidence for the v0.1.0 and v0.1.1 releases; their own source guards preserve those controllers, and the forward controller does not inspect or reinterpret their contents.
The active `publish-release.yml` workflow, displayed as `Publish release`, is the only forward release controller.
`release/current-controller.json` contains one fixed-schema `current` and `predecessor` identity pair; each identity contains its canonical stable tag, exact release commit, and annotated tag object, while `current` also carries the non-empty, unique numeric Minecraft versions selected for representative published-client checks.
That pair selects the default documentation release; it does not restrict publication to the newest product commit.
Dispatch `Publish release` from `master` with the exact `tag`, its full forty-character `source_commit`, `operation=release` or `verify`, and matching confirmation (`release vX.Y.Z` or `verify vX.Y.Z`).
The selected signed annotated tag must already exist and point directly to that product commit; the controller never moves tags.
`select-release-source.sh` resolves the preceding stable tag once and freezes both tag objects and commits as job outputs.
Later commits or release tags do not replace this selection.
The predecessor must be an ancestor of the selected product, and the product must be contained in the frozen controller history.
Representative clients come from the first, middle and last numerically sorted runtime versions in the selected source (deduplicated for smaller inventories).
Every representative must own regular runtime and integration project blobs before the build and exactly one generated Maven artifact and Modrinth manifest entry afterward.
The controller reads the metadata and every controller-owned verifier as regular Git blobs from the exact controller commit, validates their Git modes and hashes with replacement objects disabled, and requires the frozen controller commit to remain an ancestor of `origin/master`; ordinary master advancement is allowed, while removal from its history fails.
Both identities must resolve to annotated tags whose signatures GitHub reports as verified, whose tag objects and target commits match the frozen selection, and whose root project versions match their tags.
All release tags matching `refs/tags/v*` are covered by `release/github-release-tag-ruleset.json` and its audited receipt.
The wildcard ruleset has no bypass actors, forbids update including fetch-and-merge, and forbids deletion; its audited revision must remain unchanged at every release mutation boundary.
The unprotected preflight freezes both identities as job outputs only after exact-controller, signed-tag, required-CI, root-version, ancestry, wildcard-ruleset, and current/controller Pages provenance checks pass.
The protected job materializes the same controller bundle, validates the frozen source selection independently of the default documentation metadata, and re-fetches and compares both tag identities, their ancestry, the frozen controller ancestry, the wildcard ruleset, Pages evidence, and the clean tagged workspace before each external mutation.
The ordered publication project matrix owns the actual Maven artifact IDs, aggregate publication tasks, and the non-empty, unique `build/release/maven-coordinates.txt` inventory; immutable tags created before that generator retain a controller-validated fallback to their tracked legacy exact-coordinate inventory.
Central inventory sizes come from the selected generated or immutable-legacy inventory and the publication suffix, detached-signature, and checksum relationships; Modrinth and GitHub inventory sizes come from the generated manifest and the one-signature-per-JAR plus `SHA256SUMS` bundle relationship.
Receipt fields must equal those derived inventories, so adding or removing a supported runtime changes the generated evidence without requiring an artifact-list, release-number, or fixed-count edit in the controller.

## Publication order and service state

The three boolean inputs are independent and default to `true`:

| Input | When enabled | When disabled |
| --- | --- | --- |
| `maven_central` | Publish a wholly absent release, or verify and reuse an exact publication | Never invoke Maven publication; require existing exact Central evidence |
| `github_release` | Create or resume the immutable GitHub Release (`verify` checks it without publishing) | Skip GitHub Release lookup, writes, uploads and verification |
| `modrinth` | Stage, submit or finalize the applicable Modrinth lifecycle; verify public files | Skip Modrinth API calls and require no Modrinth token |

At least one destination must be enabled.
For example, use `maven_central=false`, `github_release=true`, `modrinth=false` to finish GitHub publication from already published Maven artifacts.
Skipping Maven publication does not skip canonical artifact checks: selected downstream destinations consume the verified Central artifacts, including their original detached signatures.
The signing and Publisher Portal verification credentials remain necessary for that evidence; missing, partial or conflicting Central content fails before downstream writes.
The switches come from immutable workflow inputs and cannot be enabled by a later build step.
`verify` never publishes to Maven or GitHub and only performs Modrinth finalization when that destination is selected.

Central preflight distinguishes wholly absent content from a complete exact publication in both the public repository and authenticated Publisher Portal.
Only the wholly absent pair with `maven_central=true` may invoke the single Vanniktech publication task; partial, conflicting, or cross-service state stops before any write, while an exact publication is verified and reused idempotently.
Representative client task paths are generated from the frozen metadata array rather than written into the workflow, and the verified controller bundle derives the setup-java matrix from the tagged source's version catalog.
Enabled Maven Central and GitHub publication phases complete before enabled Modrinth review submission, so an externally pending Modrinth approval does not block or roll back either public service.
GitHub Release lookup enumerates the complete paginated release inventory with the protected token, including drafts; the published-only tag endpoint cannot prove that no draft exists.
Duplicate release identities, multiple entries for the requested tag, malformed pages, inaccessible pages, and the bounded pagination limit all fail closed before creation or publication.
An existing draft retains its release ID and must match the exact title, body, lifecycle and every uploaded asset before missing files can be appended or publication can resume.
Publishing uses the verified release ID and semantic-version-based `make_latest=legacy`, so an older release does not displace a newer latest version.
The controller-owned read helper retries only bounded read requests, including asset downloads after transient GitHub errors; it never retries creation, uploads or publication blindly.
Normal Modrinth staging accepts only the generated predecessor or current project-body lineage, appends only missing manifest entries, and never replaces historical versions.
After the selected publication phases complete, the release job reads the exact submission receipt and finalizes the canonical project body when Modrinth is already approved.
It revalidates the controller, both signed release identities, their order and ancestry, the tag ruleset, protected Pages evidence, and the clean tagged source immediately before that write; a processing project or identity-bound backlog recovery remains deferred to `operation=verify`.
Before protected final verification starts, an unprotected fresh-runner job with no repository-token permissions, checkout credential, or release secret anonymously loads the same exact-controller metadata, fetches both public tags, and runs Skill preview and installation checks against both frozen source trees.
The secret-bearing verification job then re-proves the complete predecessor release from a detached worktree with that source's own Portal, Central, GitHub bundle, Modrinth, Pages, and tagged Skill contracts before invoking the same idempotent current project-body finalizer and complete verification.
The current and predecessor body states are monotonic across every controller sharing the legacy release concurrency group, and unrelated body, metadata, status, tag, controller, artifact, or public-service drift fails closed.

## Provenance and protected execution

The release workflow definition is controlled by the master commit frozen when the run starts, while the product source is the separately signed and protected release-tag commit.
Its first job has no protected environment or write-capable token, requires the tag commit to be an ancestor of that controller, reads the release version from the tag tree, and requires successful JVM and Qodana runs independently for both commits.
It selects a successful push-triggered Documentation run proving the selected release and retrieves that producer's separate unexpired controller and release-evidence artifacts, verifying both downloaded ZIP sizes and SHA-256 digests against immutable Actions metadata.
Each producer gives its artifact a run-ID-, job-, and producer-attempt-qualified name, freezes its exact artifact ID and digest as a job output, and lets a deploy-only rerun reuse those upstream outputs instead of guessing from the overall run attempt; the deploy job revalidates those identities through the API, downloads both exact IDs, and gives the exact controller name to the Pages deployer.
The post-deployment verifier reads every all-attempt job and artifact API page, rejects incomplete counts, duplicate IDs, invalid creation timestamps, unexpected artifact names, or foreign run and commit bindings, selects exactly one artifact inside each latest successful producer's logical execution window, and binds that artifact's name-suffix attempt back to the matching all-jobs history while requiring the successful deploy job to belong to the overall run attempt.
GitHub may include ancillary analysis check runs in the job inventory; their records remain subject to complete-inventory validation, but only the required producer and deployment job names can establish release evidence, and each required name must have one unambiguous execution per attempt.
The sealed tag-workflow compatibility path retains its historical fixed artifact name but requires exactly one artifact inside the latest successful build job's execution window; the subsequent receipt and byte-equivalence checks prevent any selected rerun pair from being mixed or tampered.
The release evidence artifact's `/releases/{version}/` subtree must carry the exact tag receipt, while the controller artifact root must carry its exact `master` receipt and the matching immutable subtree must remain pinned to the tag.
The read-only release-evidence job freezes the exact master-owned staging-tool blob identities, checks out and independently regenerates the exact current tag without the build cache, and only then materializes the tools from those controller blobs as read-only files that it revalidates before and after rebuilding every canonical immutable subtree.
The verifier safely validates and traverses only regular files and directories from both immutable subtrees, then compares every regular-file path, size, and SHA-256 digest; empty directories are transport-neutral because archive and ZIP transports may omit them, while any file below one still participates in the complete comparison.
For predecessor verification, the target identity selects the predecessor subtrees while a separate evidence-root identity remains bound to the current tag; the comparator checks the current root against both current subtrees, the predecessor subtrees against each other, and the complete immutable release inventory.
Publication selects a successful master Pages producer whose committed documentation metadata identifies the selected product, independently of the active publication controller.
Discovery searches the latest 100 successful master Pages runs contained in the frozen controller history; no match, missing archives or expired artifacts stops publication and requires fresh evidence.
The producer SHA and six-field artifact/deployment record are frozen separately from the workflow SHA.
After fetching every API page and sorting validated timestamps with numeric IDs as deterministic tie-breakers, default verification still requires the newest Pages deployment and latest bound successful status.
Publication explicitly requests historical verification: it may reuse the selected producer after a later authorized master deployment, and may accept an inactive status only if that exact deployment previously succeeded from its bound Documentation run.
A latest failed/pending status, an inactive deployment without bound success, or a newer deployment through the retired Pages environment remains an error.
The global deployment query is not filtered by environment; after ignoring unrelated environments, a newer deployment through the retired Pages environment cannot be hidden.
The active `github-pages-controller` environment must disable administrator bypass and use custom deployment policies containing exactly one branch rule for `master`.
The retired `github-pages` environment must also disable administrator bypass and use custom deployment policies with an empty branch-and-tag rule set, so rerunning an older workflow definition cannot enter either Pages deployment environment.
The public receipts are supporting propagation evidence rather than the authoritative source binding: GitHub Pages' Fastly layer can retain a response for 600 seconds despite request cache directives or unique queries, so default verification independently polls the root `master` receipt and immutable release receipt for up to 900 seconds and accepts each matching response only when its final HTTP response has no `Age` header or an age of at most five seconds.
Historical publication polls only the selected immutable release receipt; advancing root documentation does not alter the product proof.
It then repeats the complete Actions artifact and deployment verification and requires the six-field compatibility record to remain identical; in ordinary forward verification its release and controller run IDs and deployment IDs are equal because both artifacts and the only deployment belong to one master run.
Sealed historical final-verification workflows may instead supply their exact tag-owned Documentation run together with the current master controller run.
That compatibility path binds the historical run, jobs, unexpired `github-pages` artifact, deployment, and successful status to the requested tag and commit; materializes the missing comparator from its exact regular blob in the controller commit; and requires both the historical artifact root and its immutable release subtree to match the independently regenerated target subtree byte for byte.
Only then does the protected write-enabled job check out the verified forty-character tag commit directly at the workspace root, confirm that its `HEAD` is the product source, and use an exact controller-commit guard to load the wildcard ruleset verifier, contract, audited receipt, Pages verifier, and public-receipt waiter as regular Git blobs from fixed paths in the already verified controller commit.
The guard disables Git replacement objects, requires each controller tool's declared regular-blob mode (`100644` or `100755`), validates every materialized blob hash and syntax, makes the temporary bundle read-only, and revalidates the complete bundle immediately before every later ruleset or Pages use.
The bundle is removed by an always-running terminal step whose target is bound to the initialization step output and restricted to the job-specific runner-temporary prefix.
Those controller-owned tools repeat the frozen-controller ancestry, signed tag-object, ruleset, environment policy, both Actions artifacts, selected successful deployment, immutable-subtree comparison, and public receipt checks after environment approval.
Every protected Gradle invocation receives both `strata.sourceRevision` and `strata.sourceCommit`, so the controller commit in Actions' `GITHUB_SHA` cannot enter product documentation, manifests, or source receipts; the Actions summary records the controller commit, product-source commit, tag object, and Pages evidence separately.
Some signed historical releases predate current external-service contracts.
The forward controller may bridge those boundaries only through narrowly scoped compatibility inputs whose controller revision, allowed source paths, Git modes, byte identities, and permitted operations are fixed by checked contracts and tests.
Compatibility tools operate on isolated evidence, restore temporary state on every exit, and cannot turn partial or conflicting public content into a writable retry.
Historical public artifacts remain immutable: service verification compares remote metadata and bytes with locally rebuilt tag evidence and fails closed instead of deleting, replacing, or overwriting content.
Repeated remote ruleset, tag, controller, and source comparisons bound accidental and concurrent drift but do not make separate network operations atomic.

## Bound compatibility and recovery

Release-specific recovery contracts under `release/` are executable controller inputs, not reader guidance or templates for later releases.
The controller materializes them only while frozen metadata explicitly binds the relevant release pair; otherwise the normal path cannot access them.
Pending-review recovery first proves each original canonical manifest against its signed release source and any separately pinned legacy artifact-evidence overlay before adapting only `project.previousBodySha256` in temporary generated evidence to the exact historical body allowed by the contract.
During that body-lineage adaptation, every other manifest field remains unchanged; original manifest bytes are restored on every exit, and project bodies, artifacts, and release tags remain immutable.
Their detailed invariants live with their scripts and tests, and they must be retired atomically with every controller reference after the bound recovery is complete.

## Documentation deployment policy

First-attempt Documentation runs use a `pages-controller` concurrency group that no historical workflow definition shares, while each rerun uses a run-ID-qualified group that cannot replace a pending first attempt.
A newer master push cancels the obsolete first attempt, while a rerun cannot cancel that work and may proceed only while its frozen commit remains the exact `origin/master` head.
Every deployment reconstructs each existing release tag under its immutable `/releases/{version}/` subtree so a later deployment cannot replace release evidence.

The [documentation workflow](documentation.md#api-site-and-pages) reconstructs immutable release sites from the controller-owned inventory.
Its artifacts and deployment must satisfy the provenance checks above before protected publication continues.
A read-only source job freezes the exact controller and current release identities before the independent controller and release-evidence producers start concurrently on separate runners.
Each producer revalidates those identities before executing repository-local actions or Gradle, and deployment remains dependent on both successful producers and their independently generated artifacts.
Do not preserve incident timelines or service-status snapshots in reader guides; keep required recovery boundaries in executable contracts and tests.

## Pages artifacts and deployment

Historical tagged sites keep the publication contract from their immutable source revision, including any older guide copies.
Final release verification downloads the selected `/releases/{version}/pages-public-urls.txt` from the configured Pages origin, requests every listed path relative to that immutable base, checks its `source-revision.txt`, and polls its receipt until both the release tag and exact commit are visible in a current final HTTP response.
The Documentation workflow invokes `:integration:docs:checkDokkaPagesStaging` only from the exact `master` head; that task depends on the fully qualified root `:dokkaGenerate` and verifies the generated API site.
The workflow deploys the resulting `build/dokka/html` directory through GitHub Pages' artifact and OIDC deployment path.
Before any repository-local action runs and again immediately before deployment, the workflow requires its checkout to equal the exact `origin/master` head and requires controller metadata to identify the current annotated tag, commit, tag object, latest-release order, and ancestry.
Release-tag pushes and manual dispatches never execute the Documentation workflow; every `master` push reconstructs the release inventory from the trusted controller definition.
The deployable `github-pages` artifact and its separate immutable-release evidence artifact are retained for thirty days so a delayed protected release approval can still revalidate the exact archives from the same run.
The full-history checkout lets `release/stage-versioned-pages.sh` reproduce tagged documentation in a detached worktree on later `master` runs and copy each independently checked site into `build/dokka/html/releases/{version}` without changing the current root documentation or recursively nesting older release trees.
Repository settings must select GitHub Actions as the Pages source, configure `github-pages-controller` with administrator bypass disabled and exactly the `master` branch policy, and retire `github-pages` with administrator bypass disabled and no branch or tag policy.
The build job receives read access to actions and contents so it can freeze the upload-pages artifact digest that the composite action does not expose, the release-evidence job receives only read access to contents, and only the deploy job receives read access to actions, contents, and deployments plus `pages: write` and `id-token: write`.

Each immutable tagged site retains its own public-path inventory and source receipt below `releases/{version}`.
The [documentation build](documentation.md#api-site-and-pages) owns API generation and staging checks; the release controller verifies artifact identity and deployment provenance.
