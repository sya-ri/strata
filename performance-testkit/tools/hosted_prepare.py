"""Prepare the unchanged whole blit fixture once using existing Gradle/JMH adapters."""
import os
from pathlib import Path
import shutil
import subprocess

from cpu_plan import digest, require, write_new
from cpu_mode import Mode
from cpu_variant import Variant


def prepare(campaign, spec):
    """Build actual runtime archives, export common resolved inputs and admit both modes/variants."""
    workspace = campaign.workspace
    prepared = campaign.root / "prepared"
    prepared.mkdir()
    for variant in Variant:
        revision = spec["runtime_revisions"][variant.value]
        target = campaign.root / (variant.value + "-source")
        campaign.command("checkout-" + variant.value, ["git", "worktree", "add", "--detach", str(target), revision])
        campaign.command("archive-" + variant.value, ["git", "archive", "--format=zip", "--output=" + str(prepared / (variant.value + "-source.zip")), revision])
        # Read actual archive providers rather than adopting an arbitrary build/libs match.
        runtime_metadata = prepared / (variant.value + "-runtime-inputs.tsv")
        campaign.command("build-" + variant.value, ["bash", str(target / "gradlew"), "--no-daemon", "--no-parallel", "--max-workers=1", "-Pstrata.jvmOnly=true", "-I", str(workspace / "performance-testkit/tools/hosted_prepare.init.gradle.kts"), "-Pstrata.cpuCampaign.runtimeMetadata=" + str(runtime_metadata), ":api:jvmJar", ":runtime:core:jvmJar", ":runtime:headless:jar"], target, artifacts=[runtime_metadata])
        registered = [line.split("\t") for line in runtime_metadata.read_text().splitlines()]
        cpu_modules = {":api", ":runtime:core", ":runtime:headless"}
        require(len(registered) == 3 and {entry[1] for entry in registered} == cpu_modules, "Actual runtime project inventory changed")
        archive_paths = {}
        for kind, module, archive in registered:
            require(kind == "runtime" and Path(archive).is_file(), "Missing registered actual runtime artifact")
            output = prepared / "runtime" / variant.value / (module.strip(":").replace(":", "-") + ".jar")
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(archive, output)
            archive_paths[module] = str(output)
        write_new(prepared / (variant.value + "-runtime.json"), archive_paths)
    fixture = subprocess.check_output(["git", "show", spec["runtime_revisions"][Variant.CANDIDATE.value] + ":" + spec["fixture"]], cwd=workspace)
    target = workspace / spec["fixture"]
    require(not target.exists(), "Common fixture destination already exists")
    target.write_bytes(fixture)
    require(digest(target) == spec["fixture_sha256"], "Historical whole45 fixture source changed")
    metadata = prepared / "resolved-inputs.tsv"
    campaign.command("compile-common-fixture", ["bash", "./gradlew", "--no-daemon", "--no-parallel", "--max-workers=1", "-Pstrata.jvmOnly=true", "-I", "performance-testkit/tools/hosted_prepare.init.gradle.kts", "-Pstrata.cpuCampaign.metadata=" + str(metadata), ":quality:benchmarks:jmhCompileGeneratedClasses"])
    fields = [line.split("\t") for line in metadata.read_text().splitlines()]
    current_runtime = {Path(entry[2]).resolve() for entry in fields if entry[0] == "runtime"}
    common = []
    originals = {}
    for entry in fields:
        if entry[0] == "classpath" and Path(entry[1]).resolve() not in current_runtime:
            source = Path(entry[1])
            output = prepared / "common" / str(len(common))
            output.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, output)
            else:
                shutil.copyfile(source, output)
            common.append(str(output))
            originals[source.resolve()] = output
    require(common, "Existing JMH classpath is empty")
    controls = prepared / "control-inputs.properties"
    control_lines = []
    for entry in fields:
        if entry[0] == "control":
            require(Path(entry[2]).resolve() in originals, "Unfrozen control library")
            label = entry[1].replace(":", "\\:")
            control_lines.append(label + "=" + str(originals[Path(entry[2]).resolve()]))
    require(control_lines, "Missing actual resolved control libraries")
    controls.write_text("\n".join(control_lines) + "\n")
    commands, admissions = {}, {mode.value: {} for mode in Mode}
    for variant in Variant:
        runtime = campaign.read(prepared / (variant.value + "-runtime.json"))
        classpath = common + list(runtime.values())
        command = [campaign.java, "-Dstrata.performance.benchmarks=BlitTemplateBenchmark", "-Dstrata.performance.mode={mode}", "-Dstrata.performance.quick=false", "-Dstrata.performance.smoke=false", "-Dstrata.performance.inputs=" + str(controls), "-Dstrata.performance.cpuProbe={workspace}/performance-testkit/tools/cpu_host.py", "-cp", os.pathsep.join(classpath), "dev.s7a.strata.quality.benchmark.HistoricalPerformanceEvidence", "{output}", "{repetition}", ".*", "-bm", "{mode}", "-wi", "3", "-w", "1s", "-i", "5", "-r", "1s", "-f", "1", "-t", "1", "-tu", "us", "-foe", "true", "-prof", "gc"]
        for mode in Mode:
            output = prepared / "admissions" / mode.value / variant.value
            invocation = [token.format(workspace=workspace, mode=mode.value, output=output / "unused", repetition=0) for token in command]
            invocation.insert(1, "-Dstrata.performance.cpuAdmission=" + str(output))
            campaign.command("admit-" + mode.value + "-" + variant.value, invocation, artifacts=[output / "admission.json"])
            actual = campaign.read(output / "admission.json")
            require(len(actual["registered_workloads"]) == 45, "Compiled whole45 inventory is incomplete")
            admissions[mode.value][variant.value] = str(output / "admission.json")
        command.insert(1, "-Dstrata.performance.workloads={methods}")
        command.insert(1, "-Dstrata.performance.parameters={parameters}")
        command.insert(1, "-Dstrata.performance.cpuContext={context}")
        # run_shard supplies the exact relocated frozen observer path in this context.
        command = [token.replace("{workspace}/performance-testkit/tools/cpu_host.py", "{workspace}/" + str((campaign.root / "plan/cpu_host.py").relative_to(workspace))) for token in command]
        commands[variant.value] = command
    collector = campaign.read(Path(admissions[Mode.AVERAGE.value][Variant.BASELINE.value]))["sources"]["collector"]["sha256"]
    collector_paths = [path for path in common if Path(path).is_file() and digest(path) == collector]
    require(len(collector_paths) == 1, "Common collector does not match actual admission")
    # The JSON comparator loads the kit and resolved libraries; fixture/generated directories stay collection-only.
    processor = collector_paths + [str(originals[Path(entry[2]).resolve()]) for entry in fields if entry[0] == "control"]
    processor += list(campaign.read(prepared / "baseline-runtime.json").values())
    processor = list(dict.fromkeys(processor))
    require(all(Path(path).is_file() for path in processor), "Comparator requires actual regular JAR sources")
    return {"id": "owned-blit", "admissions": admissions, "source_archives": {variant.value: str(prepared / (variant.value + "-source.zip")) for variant in Variant}, "commands": commands}, processor
