# Fixed hosted CPU campaign

Use this manual workflow for the fixed complete blit campaign under the [CPU executor contract](cpu-executors.md).
Actual executor qualification and explicit dispatch authorization are required before collection.
Source review and synthetic transport tests do not establish qualified hosts, actual common admission, SDK integration or measured scheduling results.
The preparation init script and hosted integration still require execution verification on the admitted workers.

The workflow creates five independent held jobs through the existing GitHub Actions platform: one coordinator and four fixed workers.
Every worker keeps its original job, boot and partition through S0/P0/S1/P1/S2/P2.
No worker pool, SSH prerequisite, new service, dependency or paid capacity is introduced.
The existing Java setup action installs catalog-declared toolchains; the common historical fixture and baseline admission run only after actual qualification.

## Qualification and preparation

The coordinator collects immutable readiness bundles containing each job's actual observer profile, workspace and official SDK code hashes.
It reads one unedited qualification comment for the exact campaign from the selected issue and verifies the reviewer's repository admin/maintain permission.
The comment contract is `strata-hosted-cpu-qualification-v1`, with accepted=true, the exact run commit and all five job roles.
Each job entry contains its actual profile_sha256, sdk, workspace, valid_until_unix_ms and six proofs: physical_independence, exclusive_occupancy, fixed_affinity, authoritative_power_policy, same_job_and_boot_lifetime and permission_and_memory_disk_capacity.
Each proof retains its authoritative source bytes and SHA256.
The reviewer must establish these conditions from actual sources; a label, VM UUID, text assertion or synthetic test profile does not establish physical independence or exclusivity.
The worker jobs must also have sufficient artifact storage, memory, disk and permission for the complete original workload and retained failures.
Missing observer sources or qualification prevent preparation and collection.
No current source proves that standard ubuntu-24.04 hosted jobs satisfy these requirements, and the controller has no fallback that silently weakens them.

Preparation builds the baseline and candidate runtime archives from the original pinned revisions using actual Gradle archive providers.
It compiles the original whole blit fixture once through existing generated JMH tasks, exports the resolved classpath and external control libraries, and substitutes only the three actual runtime module artifacts.
The existing collector produces complete45 admissions for baseline/candidate in both modes, including the original deterministic verifyWork checks, before the generic driver freezes the plan.
Workers receive those exact common bytes and never rebuild fixtures during collection.
Actual common admission, runtime compatibility and preparation cost remain unverified.

## Collection and transfer

The original15 cases times initialFrame/paintRebuild/cleanFrame are divided 4/4/4/3 cases across the fixed workers.
Each scheduling side collects both avgt and sample, baseline/candidate interleaved across all three measurement repetitions, with original3x1s warmup,5x1s measurement,one fork,one thread,GC,us and fail-on-error settings.
Quick and smoke remain false.
Each wall pair adopts the first complete passing serial and parallel attempt, with at most two whole attempts; earlier partial/failure sources remain visible.
A worker whose collector throws returns its complete available source and releases without reuse.
Unknown ownership never enters a retry.

The adapter invokes the preserved official upload/download action bundles inside existing owned phases and keeps their actual acknowledgements, code hashes, raw API responses and full logs.
Every envelope binds repository/run/attempt/commit/campaign/role/sequence and inventories all regular file bytes.
Duplicate, omitted, changed, linked, traversing or mixed bundles fail before adoption.
Returned-shard phases include SDK polling/download, validation and complete restoration, bound to the actual plan/scheduling/whole attempt/shard and shard.json hash.
The no-failure path includes24 complete result uploads and24 returned-shard downloads, with setup, permits, readiness and terminal transfers accounted separately.
A coordinator failure publishes a best-effort abort artifact that polling workers observe; active owned work remains subject to the hard deadline.

## Costs, release and final export

The original Linux subreaper owns each campaign command tree, including Python, the actual SDK processes, Gradle and JMH descendants.
Final inclusive owned-tree CPU plus disjoint Node owner CPU supplies the measured campaign CPU scope.
Per-phase and JMH costs are retained attribution and are not added to that inclusive value again.
Controller-only CPU includes synchronous local copying, freeze, hashes and report processing.
The complete owner interval includes qualification, preparation, all attempts and retries, comparisons, report assembly and final reporting transfers.
Phase identities and all whole/shard/invocation/fork/iteration identities are audited globally across the three wall pairs.
Every owner's final complete log and terminal pack/upload receipt is emitted after its immutable report bundle to the durable job log, avoiding a self-referential reporting upload.

The Node owner has a330-minute deadline plus30-second emergency cleanup allowance within the workflow's360-minute limit.
Expired capacity, incomplete logs, unknown release or exhausted resource bounds produce an incomplete result without truncating the workload.
The earlier4h33m36 forecast covers only the six original measurement sides; it excludes qualification/preparation/transfers/retries/processing and remains a forecast.

After the workflow completes, run the separate read-only Linux observer:

```text
python3 -B performance-testkit/tools/hosted_audit.py sya-ri/strata RUN_ID RUN_ATTEMPT COMMIT_SHA FRESH_OUTPUT_DIRECTORY
```

It uses the installed GitHub CLI to preserve the exact completed attempt, all five jobs/steps/logs and terminal artifacts, verifies final owned release and reconciles upload acknowledgements with actual service IDs/digests.
It records its own later export CPU and wall separately from the hosted campaign.
Service job bookends describe a wider held-job wall scope; they do not establish physical host release, pure queue delay or platform CPU.
Unavailable platform values remain null, and all_cost_acceptance/runtime_or_native_acceptance remain false.
Passing this whole45 wall campaign alone does not satisfy the original complete353 runtime/native acceptance or final repository gates.

Primary implementation references: [official uploader](https://raw.githubusercontent.com/actions/upload-artifact/v7/action.yml), [official downloader](https://raw.githubusercontent.com/actions/download-artifact/v8/action.yml), [job metadata and logs](https://docs.github.com/en/rest/actions/workflow-jobs), [GitHub CLI API](https://cli.github.com/manual/gh_api) and [workflow limits](https://docs.github.com/en/actions/reference/limits).
