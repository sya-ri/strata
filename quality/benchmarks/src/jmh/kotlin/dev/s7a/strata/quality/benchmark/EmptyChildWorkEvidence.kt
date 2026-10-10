@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.quality.fixture.EmptyChildControls
import dev.s7a.strata.quality.fixture.EmptyChildFixture
import dev.s7a.strata.quality.fixture.EmptyChildPresentationControl
import dev.s7a.strata.quality.fixture.EmptyChildProbe
import dev.s7a.strata.quality.fixture.EmptyChildWorkload
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Untimed qualification of the identical precompiled source against the actual loaded baseline or candidate archives.
 * The shared kit owns all measurement and fork provenance; this executable records independent behavior/work evidence only.
 */
public object EmptyChildWorkEvidence {
    /**
     * Requires one fresh JSON evidence file; both runtime variants use this same executable and all controls.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1)
        val output = Path.of(args.single()).toAbsolutePath().normalize()
        require(Files.exists(output).not())
        val controls = JsonObject()
        EmptyChildControls.Control.entries.forEach { control ->
            EmptyChildControls.verify(control)
            controls.addProperty(control.name, "passed")
        }
        EmptyChildControls.verifyInventory()
        verifyPixels()
        verifyTerminalReferences()
        val work = JsonObject()
        EmptyChildWorkload.entries.forEach { workload ->
            listOf(false, true).forEach { monitoring ->
                EmptyChildFixture(workload, monitoring).use { fixture ->
                    fixture.execute()
                    val row = JsonObject()
                    EmptyChildProbe.Stage.entries.forEach { row.addProperty(it.name, fixture.probe.count(it)) }
                    fixture.monitor?.snapshot()?.let { snapshot ->
                        val diagnostics = JsonObject()
                        snapshot.counts.toSortedMap(compareBy { it.ordinal }).forEach { (metric, count) -> diagnostics.addProperty(metric.name, count) }
                        row.add("diagnostics", diagnostics)
                        row.addProperty("active_subscriptions", snapshot.activeSubscriptions)
                        row.addProperty("overflowed", snapshot.overflowed)
                    }
                    work.add("$workload/$monitoring", row)
                }
            }
        }
        val runtime = JsonObject()
        listOf(Element::class.java, UiTree::class.java, HeadlessImage::class.java).forEach { type ->
            val source = Path.of(checkNotNull(type.protectionDomain.codeSource).location.toURI()).toAbsolutePath().normalize()
            require(Files.isRegularFile(source) && source.fileName.toString().endsWith(".jar")) { "Qualification requires actual runtime archives: $type/$source" }
            runtime.add(type.name, JsonObject().apply {
                addProperty("source", source.toString())
                addProperty("sha256", ArtifactIdentity.fullCodeSource(type))
            })
        }
        val result = JsonObject().apply {
            addProperty("contract", "strata-empty-child-qualification-v1")
            addProperty("status", "passed")
            add("controls", controls)
            add("work", work)
            add("runtime", runtime)
            add("fixtures", GsonBuilder().create().toJsonTree(ArtifactIdentity.applicationTrees(listOf(EmptyChildBenchmark::class.java, EmptyChildFixture::class.java))))
            addProperty("empty_dynamic_list_iterations", EmptyChildControls.emptyMatchingIterations())
            addProperty("pixel_oracle", "exact-opaque-three-pixel-v1")
            addProperty("terminal_reference_assertions", "passed")
            addProperty("java_version", System.getProperty("java.version"))
            addProperty("java_vendor", System.getProperty("java.vendor"))
        }
        Files.createDirectories(checkNotNull(output.parent))
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(result), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    /**
     * Compares real loaded headless pixels with an independently specified color array after equal and changed updates.
     */
    public fun verifyPixels() {
        val scenes = EmptyChildPresentationControl.verify()
        val black = 0xFF000000.toInt()
        val blue = black or 1
        val expected = listOf(intArrayOf(black, blue, blue), intArrayOf(black, blue, blue), intArrayOf(blue, blue, black))
        scenes.forEachIndexed { index, commands ->
            val image = rasterizeHeadless(commands, IntSize(3, 1))
            check(image.copyArgb().contentEquals(expected[index]))
        }
    }

    /**
     * Inspects reachable runtime ownership directly after close and after provisional-create failure, without relying on GC.
     */
    public fun verifyTerminalReferences() {
        val probe = EmptyChildProbe()
        val tree = UiTree()
        tree.update(probe.element(0, children = listOf(probe.element(1))))
        tree.close()
        terminalTree(tree)
        val broken = UiTree()
        broken.update(probe.element(2))
        val cause = IllegalStateException("provisional child creation")
        probe.failure = EmptyChildProbe.Failure(EmptyChildProbe.Stage.Create, 4, cause)
        check(runCatching { broken.update(probe.element(2, children = listOf(probe.element(3), probe.element(4)))) }.exceptionOrNull() === cause)
        terminalTree(broken)
        broken.close()
        probe.failure = null
        val session = createRuntimeUiSession { probe.element(5) }
        session.attach()
        session.frame(Constraints())
        val implementation = field(session, "session")
        val ownedTree = field(implementation, "tree")
        session.close()
        listOf("retainedContent", "tree", "cachedFrame", "cachedFrameConstraints", "committedFrameConstraints").forEach { check(nullableField(implementation, it) == null) }
        terminalTree(ownedTree)
    }

    private fun terminalTree(tree: Any) {
        check(nullableField(tree, "root") == null)
        check((field(field(tree, "reconciler"), "provisionalRoots") as Collection<*>).isEmpty())
        check((field(field(tree, "registry"), "nodes") as Collection<*>).isEmpty())
        val callbacks = field(field(tree, "pipeline"), "frameCallbacks")
        check(nullableField(callbacks, "root") == null)
        check((field(callbacks, "cutoff") as Collection<*>).isEmpty() && (field(callbacks, "timed") as Collection<*>).isEmpty())
    }

    private fun field(owner: Any, name: String): Any = checkNotNull(nullableField(owner, name))

    private fun nullableField(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(owner)
}
