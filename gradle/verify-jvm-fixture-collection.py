"""Compile an unregistered fixture and certify its scoped collection through the ordinary launcher."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


FIXTURE = """package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.nio.file.Files

/** Synthetic acceptance fixture, discovered exclusively through generated JMH metadata. */
@OptIn(InternalStrataRuntimeApi::class)
@State(Scope.Thread)
public open class ScopedModelFixtureBenchmark {
    /** Two compiled widths; explicit selection retains exactly one. */
    @JvmField
    @Param("8", "16")
    public var width: Int = 8
    private lateinit var commands: List<DrawCommand>

    /** Loads the immutable compiled fixture resource outside sampling. */
    @Setup(Level.Trial)
    public fun setup() {
        val argb = checkNotNull(javaClass.getResourceAsStream("/scoped-model-fixture.txt")).bufferedReader().use { it.readText().trim().toLong(16).toInt() }
        commands = listOf(DrawCommand.FillRectangle(IntRect(0, 0, width, width), ArgbColor(argb)))
    }

    /** Allocates and paints a fresh output from the prepared commands. */
    @Benchmark
    public fun paint(): HeadlessImage = rasterizeHeadless(commands, IntSize(width, width))

    /** Owns the complete fixture contract independently of the collection script. */
    public companion object {
        /** Checks generated metadata, the extra input, output ownership and exact pixels before forks start. */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(ScopedModelFixtureBenchmark::class.java), setOf("avgt")).size == 2)
            val input = checkNotNull(JmhFixtureSelection.inputs()["scoped-model-input"])
            val compiled = checkNotNull(ScopedModelFixtureBenchmark::class.java.getResourceAsStream("/scoped-model-fixture.txt")).use { it.readBytes() }
            check(Files.readAllBytes(input).contentEquals(compiled)) { "Fixture external input differs from compiled resource" }
            listOf(8, 16).forEach { width ->
                val state = ScopedModelFixtureBenchmark().apply { this.width = width; setup() }
                val image = state.paint()
                check(image.size == IntSize(width, width) && image.copyArgb().all { it == 0xFF173A59.toInt() })
                image.copyArgb().fill(0)
                check(image.argbAt(0, 0) == 0xFF173A59.toInt())
            }
            println("Verified unregistered scoped fixture, extra input and both compiled widths")
        }
    }
}
"""


def main():
    """Keep generated sources temporary and preserve all success and failure evidence in build outputs."""
    root = Path(__file__).resolve().parent.parent
    module = root / "quality/component-benchmarks"
    source = module / "src/jmh/kotlin/dev/s7a/strata/quality/benchmark/ScopedModelFixtureBenchmark.kt"
    resource = module / "src/jmh/resources/scoped-model-fixture.txt"
    if source.exists() or resource.exists():
        raise RuntimeError("Refusing to replace an existing fixture source or resource")
    evidence_parent = root / "build/fixture-tests/jvm-collection"
    evidence_parent.mkdir(parents=True, exist_ok=True)
    evidence = Path(tempfile.mkdtemp(prefix="invocation-", dir=evidence_parent))
    extra = evidence / "extra-input.txt"
    extra.write_text("FF173A59\n", encoding="utf-8")
    parameters = evidence / "parameters.properties"
    parameters.write_text("width=16\n", encoding="utf-8")
    inputs = evidence / "inputs.properties"
    inputs.write_text(f"scoped-model-input={extra.as_posix()}\n", encoding="utf-8")
    wrapper = ([str(root / "gradlew.bat")] if os.name == "nt" else ["bash", str(root / "gradlew")]) + [
        "--no-daemon", "--max-workers=1", "-Pkotlin.compiler.execution.strategy=in-process",
    ]
    multiplatform = {":api", ":runtime:core", ":performance-testkit"}
    benchmarks = {":quality:benchmarks", ":quality:component-benchmarks", ":quality:remote-benchmarks"}
    projects = multiplatform | benchmarks | {
        ":runtime:headless", ":runtime:minecraft", ":runtime:minecraft-fonts-lwjgl", ":runtime:remote", ":quality:detekt-rules", ":integration:api",
    }
    published = projects - benchmarks - {":quality:detekt-rules", ":integration:api"}
    tasks = [f"{owner}:{task}" for owner in sorted(projects) for task in ("formatKotlin", "lintKotlin", "detekt", "classes")]
    tasks += [f"{owner}:{task}" for owner in sorted(published) for task in ("checkKotlinAbi", "updateKotlinAbi")]
    tasks += [f"{owner}:{task}" for owner in sorted(multiplatform) for task in ("jvmTest", "jvmJar")]
    tasks += [f"{owner}:{task}" for owner in sorted(projects - multiplatform) for task in ("test", "jar")]
    tasks += [f"{owner}:{task}" for owner in sorted(benchmarks) for task in ("jmhClasses", "jmhRunBytecodeGenerator", "jmhCompileGeneratedClasses")]
    tasks += [":quality:benchmarks:jmhHistorical", ":quality:component-benchmarks:jmhComponents", ":quality:remote-benchmarks:jmhRemote", ":performance-testkit:processEvidence"]
    tasks += [":quality:benchmarks:verifyHistoricalWorkloads", ":quality:component-benchmarks:verifyComponentRenderingWork", ":integration:api:checkApiOnlyClasspath"]
    init = evidence / "project-model.init.gradle"
    init.write_text("gradle.projectsEvaluated {\n" + f"    if (gradle.rootProject.projectDir.canonicalFile != new File({json.dumps(root.as_posix())}).canonicalFile) return\n" + """
    def paths = gradle.rootProject.allprojects.findAll { it.file('build.gradle.kts').isFile() }.collect { it.path }.findAll { it != ':' }.sort()
    println('EXPLICIT_JVM_PROJECTS=' + paths.join(','))
}
""", encoding="utf-8")
    with (evidence / "task-closure.log").open("w", encoding="utf-8") as log:
        subprocess.run(wrapper + ["-Pstrata.jvmOnly=true", "--dry-run", "-I", str(init)] + tasks, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
    closure_log = (evidence / "task-closure.log").read_text(encoding="utf-8")
    selected = next(line.removeprefix("EXPLICIT_JVM_PROJECTS=") for line in closure_log.splitlines() if line.startswith("EXPLICIT_JVM_PROJECTS="))
    assert set(selected.split(",")) == projects
    assert "verifyPublishedHostInventory SKIPPED" not in closure_log and "verifyPublishedPerformanceInventory SKIPPED" not in closure_log
    (evidence / "project-model.json").write_text(json.dumps({"explicit_projects": sorted(projects), "explicit_project_count": len(projects), "requested_tasks": tasks}, indent=2), encoding="utf-8")
    arguments = [
        "-Pstrata.jvmOnly=true",
        ":quality:component-benchmarks:jmhComponents",
        "-Pstrata.performance.quick=true",
        "-Pstrata.performance.benchmarks=ScopedModelFixtureBenchmark",
        "-Pstrata.performance.workloads=ScopedModelFixtureBenchmark.paint",
        f"-Pstrata.performance.parameters={parameters}",
        f"-Pstrata.performance.fixtureInputs={inputs}",
    ]
    try:
        source.write_text(FIXTURE, encoding="utf-8")
        resource.parent.mkdir(parents=True, exist_ok=True)
        resource.write_bytes(extra.read_bytes())
        (evidence / source.name).write_text(FIXTURE, encoding="utf-8")
        with (evidence / "collection.log").open("w", encoding="utf-8") as log:
            subprocess.run(wrapper + arguments + [f"-Pstrata.performance.output={evidence / 'run-0'}"], cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
        run = evidence / "run-0"
        receipt = json.loads((run / "receipt.json").read_text(encoding="utf-8"))
        results = json.loads((run / "results.json").read_text(encoding="utf-8"))
        assert receipt["status"] == "passed" and receipt["fork_verification"] == "loaded-artifacts-per-iteration-v1"
        assert len(receipt["registered_workloads"]) == len(results) == 1
        assert any("ScopedModelFixtureBenchmark" in name for name in receipt["fixture_identity"])
        assert "scoped-model-input" in receipt["inputs"]
        assert receipt["results_sha256"] == hashlib.sha256((run / "results.json").read_bytes()).hexdigest()
        result = results[0]
        assert result["benchmark"].endswith("ScopedModelFixtureBenchmark.paint") and result["params"] == {"width": "16"}
        assert result["forks"] == result["measurementIterations"] == 1 and result["warmupIterations"] == 0
        assert result["secondaryMetrics"]["strata.provenance"]["score"] == 1
        assert "Verified unregistered scoped fixture" in (evidence / "collection.log").read_text(encoding="utf-8")
        extra.write_text("FF173A58\n", encoding="utf-8")
        with (evidence / "rejected-input.log").open("w", encoding="utf-8") as log:
            rejected = subprocess.run(wrapper + arguments + [f"-Pstrata.performance.output={evidence / 'rejected'}"], cwd=root, stdout=log, stderr=subprocess.STDOUT)
        assert rejected.returncode != 0
        assert "Fixture external input differs from compiled resource" in (evidence / "rejected-input.log").read_text(encoding="utf-8")
        assert (evidence / "rejected").exists() is False
    finally:
        source.unlink(missing_ok=True)
        resource.unlink(missing_ok=True)
    print(f"Verified new generated fixture, exact selection, extra inputs, independent fork and pre-fork rejection: {evidence}")


if __name__ == "__main__":
    main()
