package dev.s7a.strata.quality.benchmark

import com.google.gson.stream.MalformedJsonException
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftArchiveFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftDirectoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontDiagnostic
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimitException
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftIndexedFontAssetSource
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Independently expected untimed control groups for both qualified runtime variants.
 * Uses the original whole-match Regex only as a reference, never as the candidate's implementation.
 * Control receipts establish execution on their actual loaded variant; existing tests are not substituted.
 */
public object IndexedFontAssetControls {
    private val reference = Regex("[0-9a-f]{40}")
    private val valid = "0".repeat(40)
    private val limits = MinecraftFontLoadLimits()
    private val hashMessage = "Asset index object hash is invalid."

    /**
     * Runs the complete boundary corpus, filesystem/ownership controls and generated workload checks.
     */
    public fun verify() {
        val root = Files.createTempDirectory("strata-indexed-controls-").toAbsolutePath().normalize()
        try {
            Corpus(root).verify()
        } finally {
            removeOwned(root)
        }
    }

    /**
     * Proves that reused methods ignore an index that became invalid after untimed construction.
     */
    public fun verifyReuse() {
        val root = Files.createTempDirectory("strata-indexed-reuse-").toAbsolutePath().normalize()
        try {
            val manifest = IndexedFontAssetFiles.prepare(root.resolve("inputs"))
            val files = JvmPerformanceInputs.read(manifest)
            for (input in listOf(IndexedFontInput.Distinct1, IndexedFontInput.Shared1, IndexedFontInput.Distinct4096, IndexedFontInput.Shared4096)) {
                IndexedFontAssetFiles.open(input, files).use { fixture ->
                    val source = fixture.source()
                    val expected = source.paths().toList()
                    val bytes = checkNotNull(source.read(IndexedFontAssetFiles.defaultPath))
                    Files.writeString(fixture.index, "invalid JSON")
                    repeat(3) {
                        check(source.paths().toList() == expected)
                        check(checkNotNull(source.read(IndexedFontAssetFiles.defaultPath)).contentEquals(bytes))
                    }
                }
            }
        } finally {
            removeOwned(root)
        }
    }

    private fun removeOwned(root: Path) {
        require(root.isAbsolute && root.fileName.toString().startsWith("strata-indexed-"))
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                require(path.toAbsolutePath().normalize().startsWith(root))
                Files.delete(path)
            }
        }
    }

    @Suppress("TooManyFunctions") // Named filesystem and ownership operations belong to this single temporary owner.
    private class Corpus(
        private val root: Path,
    ) {
        private val index = root.resolve("index.json")
        private val objects = Files.createDirectory(root.resolve("objects"))
        private val completed = linkedSetOf<Control>()

        @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod") // Preserve the Issue's explicit ordered 32 control groups.
        fun verify() {
            control(Control.Empty) {
                check(source("{\"objects\":{}}").paths().isEmpty())
                check(source("{\"objects\":{}}").read(IndexedFontAssetFiles.defaultPath) == null)
            }
            control(Control.ValidHashes) { listOf(valid, "f".repeat(40), "0123456789abcdef".repeat(2) + "01234567").forEach { hash -> hash(hash, true) } }
            control(Control.ValidPositions) { positions("0123456789abcdef".toList(), true) }
            control(Control.InvalidAscii) { positions((33..126).map(Int::toChar).filter { "0123456789abcdefABCDEF".contains(it).not() }, false) }
            control(Control.Uppercase) { positions(('A'..'F').toList(), false) }
            control(Control.Lengths) {
                listOf(0, 1, 39, 41, 1024).forEach { hash("0".repeat(it), false) }
                hash("g".repeat(40), false)
            }
            control(Control.Whitespace) { positions(listOf(' ', '\t', '\r', '\n', '\u0000'), false) }
            control(Control.NonAscii) { positions(listOf('\u0660', '\u06F0', '\uFF10', '\uFF41', '\u0430', '\u03B1'), false) }
            control(Control.Surrogates) {
                positions(listOf('\uD800', '\uDBFF', '\uDC00', '\uDFFF'), false)
                listOf(0, 19, 38).forEach { offset -> hash(valid.replaceRange(offset, offset + 2, "\uD83D\uDE00"), false) }
            }
            control(Control.Escapes) {
                check(source(single(quote(valid))).paths() == setOf("assets/test/data"))
                check(source(single("\"$valid\"")).paths() == setOf("assets/test/data"))
                failure<IllegalArgumentException>(hashMessage) { source(single("\"${"0".repeat(39)}\\u0041\"")) }
                hash("0".repeat(39) + '\n', false)
            }
            control(Control.HashShapes) {
                listOf("null", "1", "true", "[]", "{}").forEach { value -> failure<IllegalArgumentException>("Expected a JSON string.") { source(single(value)) } }
                failure<IllegalArgumentException>("Expected a JSON string.") { source("""{"objects":{"test/data":{}}}""") }
                listOf("null", "[]", "1", "\"hash\"").forEach { value -> failure<IllegalArgumentException>("Expected a JSON object.") { source("""{"objects":{"test/data":$value}}""") } }
            }
            control(Control.DocumentShapes) {
                listOf("{}", "{\"objects\":null}", "{\"objects\":[]}", "[]", "1").forEach { json -> failure<IllegalArgumentException>("Expected a JSON object.") { source(json) } }
                failure<MalformedJsonException>() { source("{\"objects\":]}") }
            }
            control(Control.InvalidPositions) {
                listOf(0, 1, 2).forEach { invalid ->
                    val entries = (0..2).map { offset -> "test/$offset" to if (offset == invalid) "A".repeat(40) else valid }
                    failure<IllegalArgumentException>(hashMessage) { source(records(entries)) }
                }
            }
            control(Control.FailFastOrder) {
                failure<IllegalArgumentException>(hashMessage) { source(records(listOf("test/first" to "A".repeat(40), "../later" to valid))) }
                failure<IllegalArgumentException>("Font asset paths cannot contain empty or parent-traversal segments.") { source(records(listOf("../first" to valid, "test/later" to "A".repeat(40)))) }
                check(source(records(listOf("test/z" to valid, "test/a" to valid, "test/m" to valid))).paths().toList() == listOf("assets/test/z", "assets/test/a", "assets/test/m"))
            }
            control(Control.Aliases) {
                val bytes = byteArrayOf(1, 2, 3)
                writeObject(valid, bytes)
                val source = source(records(listOf("test/a" to valid, "test/b" to valid)))
                check(source.paths().toList() == listOf("assets/test/a", "assets/test/b"))
                check(checkNotNull(source.read("assets/test/a")).contentEquals(bytes))
                check(checkNotNull(source.read("assets/test/b")).contentEquals(bytes))
            }
            control(Control.PathHashPrecedence) {
                failure<IllegalArgumentException>("Font asset paths must be canonical relative paths.") { source(records(listOf("/absolute" to "bad"))) }
                failure<MinecraftFontLoadLimitException>() { source(records(listOf("test/data" to "bad")), limits.copy(maxPathLength = 8)) }
            }
            control(Control.Paths) {
                check(source(records(listOf("test/folder/data" to valid))).paths() == setOf("assets/test/folder/data"))
                listOf("", "/absolute", "test\\data", "C:/data", "test:bad").forEach { path -> failure<IllegalArgumentException>("Font asset paths must be canonical relative paths.") { source(records(listOf(path to valid))) } }
                listOf("../data", "test/../data", "test/./data", "test//data", "test/data/").forEach { path -> failure<IllegalArgumentException>("Font asset paths cannot contain empty or parent-traversal segments.") { source(records(listOf(path to valid))) } }
            }
            control(Control.PathCeilings) {
                val json = records(listOf("test/data" to valid))
                check(source(json, limits.copy(maxPathLength = 16)).paths().single().length == 16)
                failure<MinecraftFontLoadLimitException>() { source(json, limits.copy(maxPathLength = 15)) }
            }
            control(Control.ByteCeilings) {
                val json = "{\"objects\":{}}"
                val size = json.toByteArray(Charsets.UTF_8).size
                val exact = limits.copy(maxDocumentBytes = size, maxInputBytes = size.toLong())
                check(source(json, exact).paths().isEmpty())
                for (bounded in listOf(exact.copy(maxDocumentBytes = size - 1), exact.copy(maxInputBytes = size.toLong() - 1))) failure<MinecraftFontLoadLimitException>() { source(json, bounded) }
            }
            control(Control.EntryCeilings) {
                val json = records(listOf("test/a" to valid, "test/b" to valid))
                check(source(json, limits.copy(maxSourceEntries = 2)).paths().size == 2)
                failure<MinecraftFontLoadLimitException>() { source(json, limits.copy(maxSourceEntries = 1)) }
            }
            control(Control.JsonCeilings) {
                val json = single(quote(valid))
                check(source(json, limits.copy(maxJsonDepth = 3, maxJsonValues = 10)).paths().size == 1)
                failure<MinecraftFontLoadLimitException>() { source(json, limits.copy(maxJsonDepth = 2)) }
                failure<MinecraftFontLoadLimitException>() { source(json, limits.copy(maxJsonValues = 9)) }
                check(json.length == 277)
                failure<MinecraftFontLoadLimitException>() { source(json, limits.copy(maxDocumentBytes = json.length - 1)) }
            }
            control(Control.Constructors) {
                Files.writeString(index, single(quote(valid)))
                val default = MinecraftIndexedFontAssetSource(index, objects)
                val named = MinecraftIndexedFontAssetSource(index, objects, "selected")
                val explicit = MinecraftIndexedFontAssetSource(index, objects, "selected", limits)
                check(default.name == index.toString() && listOf(named.name, explicit.name) == listOf("selected", "selected"))
                check(default.paths() == explicit.paths() && named.paths() == explicit.paths())
            }
            control(Control.ObjectResolution) {
                val hash = "ab" + "0".repeat(38)
                val bytes = byteArrayOf(7, 8, 9)
                writeObject(hash, bytes)
                val source = source(single(quote(hash)))
                check(checkNotNull(source.read("assets/test/data")).contentEquals(bytes))
                check(source.read("assets/test/absent") == null)
                failure<MinecraftFontLoadLimitException>() { source.read("assets/test/data", limits.copy(maxAssetBytes = 2)) }
                check(checkNotNull(source.read("assets/test/data", limits.copy(maxAssetBytes = 3))).contentEquals(bytes))
            }
            control(Control.SymlinksAndIo) { symlinksAndIo() }
            control(Control.StreamClosure) {
                check(source("{\"objects\":{}}").paths().isEmpty())
                requireClosedIndex()
                failure<IllegalArgumentException>(hashMessage) { source(single("\"bad\"")) }
                requireClosedIndex()
                failure<MinecraftFontLoadLimitException>() { source("{\"objects\":{}}", limits.copy(maxDocumentBytes = 0)) }
                requireClosedIndex()
                failure<MinecraftFontLoadLimitException>() { source("{\"objects\":{}}", limits.copy(maxJsonValues = 0)) }
                requireClosedIndex()
            }
            control(Control.FreshBytes) {
                writeObject(valid, byteArrayOf(1, 2, 3))
                val source = source(single(quote(valid)))
                val first = checkNotNull(source.read("assets/test/data"))
                val second = checkNotNull(source.read("assets/test/data"))
                check(first !== second && first.contentEquals(second))
                first[0] = 99
                check(checkNotNull(source.read("assets/test/data")).contentEquals(byteArrayOf(1, 2, 3)))
            }
            control(Control.CapturedIndex) {
                val source = source(single(quote(valid)))
                Files.writeString(index, "invalid JSON")
                check(source.paths() == setOf("assets/test/data"))
                writeObject(valid, byteArrayOf(4, 5))
                check(checkNotNull(source.read("assets/test/data")).contentEquals(byteArrayOf(4, 5)))
            }
            control(Control.IndependentOwners) { independentOwners() }
            control(Control.SnapshotPriority) { snapshotPriority() }
            control(Control.SnapshotRetirement) { snapshotRetirement() }
            control(Control.CommonQualification) {
                val classes = listOf(IndexedFontAssetBenchmark::class.java, IndexedFontReuseBenchmark::class.java, PortableTextBenchmark::class.java)
                check(JmhWorkloadInventory.capture(classes, setOf("avgt", "sample")).size == 94)
                check(classes.map { it.protectionDomain.codeSource.location }.distinct().size == 1)
                check(reference.matches(valid) && reference.matches("A".repeat(40)).not())
                val runtime = LoadedArtifactMetadata.capture(ClassLoader.getSystemClassLoader(), mapOf("minecraft" to MinecraftIndexedFontAssetSource::class.java.name), setOf("minecraft"))
                LoadedArtifactMetadata.verifyComplete(runtime)
                println("indexed-font-qualification-runtime=$runtime")
                println("indexed-font-qualification-fixtures=${LoadedArtifactMetadata.captureClassHashes(classes + listOf(IndexedFontInput::class.java, IndexedFontAssetFiles::class.java, IndexedFontAssetControls::class.java))}")
            }
            control(Control.ZeroWorkAndPortableText) {
                verifyReuse()
                PortableTextWorkEvidence.main(emptyArray())
            }
            check(completed.toList() == Control.entries)
            println("indexed-font-controls=32; runtime=${MinecraftIndexedFontAssetSource::class.java.protectionDomain.codeSource.location}; fixture=${javaClass.protectionDomain.codeSource.location}")
        }

        private fun control(
            group: Control,
            operation: () -> Unit,
        ) {
            operation()
            check(completed.add(group))
            println("indexed-font-control=${group.ordinal + 1}:${group.name}:passed")
        }

        private fun positions(
            characters: List<Char>,
            accepted: Boolean,
        ) {
            for (character in characters) {
                for (position in listOf(0, 19, 39)) {
                    hash(valid.replaceRange(position, position + 1, character.toString()), accepted)
                }
            }
        }

        private fun hash(
            value: String,
            accepted: Boolean,
        ) {
            check(reference.matches(value) == accepted)
            if (accepted) check(source(single(quote(value))).paths() == setOf("assets/test/data")) else failure<IllegalArgumentException>(hashMessage) { source(single(quote(value))) }
        }

        private fun source(
            json: String,
            bounded: MinecraftFontLoadLimits = limits,
        ): MinecraftIndexedFontAssetSource {
            Files.writeString(index, json, Charsets.UTF_8)
            return MinecraftIndexedFontAssetSource(index, objects, "control", bounded)
        }

        private fun single(hashJson: String): String = "{\"objects\":{\"test/data\":{\"hash\":$hashJson}}}"

        private fun records(entries: List<Pair<String, String>>): String = entries.joinToString(",", "{\"objects\":{", "}}") { (path, hash) -> "${quote(path)}:{\"hash\":${quote(hash)}}" }

        private fun quote(value: String): String = value.map { character -> "\\u%04x".format(Locale.ROOT, character.code) }.joinToString("", "\"", "\"")

        private inline fun <reified T : Throwable> failure(
            message: String? = null,
            operation: () -> Any?,
        ) {
            val thrown = runCatching(operation).exceptionOrNull()
            check(thrown is T) { "Expected ${T::class.java.name}, received $thrown" }
            if (message != null) check(thrown.message == message) { "Expected '$message', received '${thrown.message}'" }
        }

        private fun writeObject(
            hash: String,
            bytes: ByteArray,
        ): Path = Files.write(Files.createDirectories(objects.resolve(hash.take(2))).resolve(hash), bytes)

        private fun requireClosedIndex() {
            FileChannel.open(index, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                checkNotNull(channel.tryLock()).use { lock -> check(lock.isValid) }
            }
            val moved = Files.move(index, root.resolve("closed-index.json"))
            Files.move(moved, index)
        }

        private fun symlinksAndIo() {
            failure<IOException>() { MinecraftIndexedFontAssetSource(root.resolve("missing.json"), objects) }
            val source = source(single(quote("c".repeat(40))))
            failure<IOException>() { source.read("assets/test/data") }
            Files.createDirectory(objects.resolve("cc"))
            failure<IOException>() { source.read("assets/test/data") }
            val outside = Files.write(root.resolve("outside.bin"), byteArrayOf(42))
            val linkHash = "d".repeat(40)
            val link = Files.createDirectories(objects.resolve("dd")).resolve(linkHash)
            Files.createSymbolicLink(link, outside)
            failure<IllegalArgumentException>("Asset object symbolic link escapes the object directory.") { source(single(quote(linkHash))).read("assets/test/data") }
            Files.delete(link)
            val folder = Files.createDirectory(root.resolve("external-folder"))
            Files.write(folder.resolve("e".repeat(40)), byteArrayOf(42))
            val prefix = objects.resolve("ee")
            Files.createSymbolicLink(prefix, folder)
            failure<IllegalArgumentException>("Asset object symbolic link escapes the object directory.") { source(single(quote("e".repeat(40)))).read("assets/test/data") }
            Files.delete(prefix)
            val directory = Files.createDirectory(root.resolve("linked-pack"))
            Files.createSymbolicLink(directory.resolve("escape"), outside)
            failure<IllegalArgumentException>("Font asset symbolic link escapes its pack directory.") { MinecraftDirectoryFontAssetSource(directory).read("escape") }
        }

        private fun independentOwners() {
            val firstIndex = Files.writeString(root.resolve("first.json"), single(quote(valid)))
            val secondIndex = Files.writeString(root.resolve("second.json"), records(listOf("test/other" to valid)))
            val executor = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val first = executor.submit<MinecraftIndexedFontAssetSource> {
                    check(start.await(30, TimeUnit.SECONDS))
                    MinecraftIndexedFontAssetSource(firstIndex, objects, "first", limits.copy(maxSourceEntries = 1))
                }
                val second = executor.submit<MinecraftIndexedFontAssetSource> {
                    check(start.await(30, TimeUnit.SECONDS))
                    MinecraftIndexedFontAssetSource(secondIndex, objects, "second", limits.copy(maxSourceEntries = 1))
                }
                start.countDown()
                val a = first.get(30, TimeUnit.SECONDS)
                val b = second.get(30, TimeUnit.SECONDS)
                check(a !== b && a.paths() == setOf("assets/test/data") && b.paths() == setOf("assets/test/other"))
                val document = """{"providers":[{"type":"space","advances":{"A":7}}]}""".toByteArray(Charsets.UTF_8)
                writeObject(valid, document)
                val budgetLimits = limits.copy(maxProviders = 1, maxInputBytes = document.size.toLong())
                val snapshots = listOf(a, b).map { indexed ->
                    executor.submit<MinecraftFontSnapshot> {
                        val callback = object : MinecraftFontAssetSource by indexed {
                            override fun paths(): Set<String> = setOf(IndexedFontAssetFiles.defaultPath)
                            override fun read(path: String): ByteArray? = if (path == IndexedFontAssetFiles.defaultPath) indexed.read(indexed.paths().single()) else null
                        }
                        MinecraftFontSnapshot.load(listOf(callback), IndexedFontAssetFiles.compatibility, IndexedFontAssetFiles.options, budgetLimits)
                    }
                }
                snapshots.forEach { future ->
                    val snapshot = future.get(30, TimeUnit.SECONDS)
                    check(snapshot.fontIds == setOf(ResourceId("minecraft", "default")) && snapshot.diagnostics.isEmpty())
                }
                Files.writeString(firstIndex, single("null"))
                failure<IllegalArgumentException>("Expected a JSON string.") { MinecraftIndexedFontAssetSource(firstIndex, objects) }
                check(b.paths() == setOf("assets/test/other"))
            } finally {
                executor.shutdownNow()
                check(executor.awaitTermination(30, TimeUnit.SECONDS))
            }
        }

        private fun snapshotPriority() {
            val manifest = IndexedFontAssetFiles.prepare(root.resolve("snapshot-inputs"))
            val files = JvmPerformanceInputs.read(manifest)
            for (input in IndexedFontInput.entries) {
                IndexedFontAssetFiles.open(input, files).use { fixture ->
                    val source = fixture.source()
                    check(source.paths().size == input.records)
                    check((source.read(IndexedFontAssetFiles.defaultPath) == null) == (input.records == 0))
                    val snapshot = fixture.loadExample()
                    val output = fixture.output(snapshot)
                    check(output.fonts.toSet() == setOf(ResourceId("minecraft", "default"), ResourceId("test", "archive"), ResourceId("test", "directory")))
                    check(output.diagnostics.isEmpty())
                    check(output.glyphs.take(3).map { it.advance } == listOf(13f, 3f, 5f))
                    check(output.glyphs.last().advance == if (input.records == 0) 6f else 2f)
                    check(providerSources(snapshot) == listOf(fixture.directory.toString(), fixture.archive.toString()) + if (input.records == 0) emptyList() else listOf("Minecraft assets"))
                    check(output == fixture.output(fixture.loadExample()))
                    val reads = mutableListOf<String>()
                    val tracked = object : MinecraftFontAssetSource by source {
                        override fun read(path: String): ByteArray? {
                            reads.add(path)
                            check(path.startsWith("assets/test/unused/").not()) { "Snapshot read an unrelated index record" }
                            return source.read(path)
                        }
                    }
                    val replay = MinecraftFontSnapshot.load(listOf(tracked, MinecraftArchiveFontAssetSource(fixture.archive), MinecraftDirectoryFontAssetSource(fixture.directory)), IndexedFontAssetFiles.compatibility, IndexedFontAssetFiles.options, limits)
                    check(output == fixture.output(replay))
                    check(reads.count { it == IndexedFontAssetFiles.defaultPath } == if (input.records == 0) 0 else 1)
                    if (1 < input.records && input.shared.not()) failure<IOException>() { source.read("assets/test/unused/1.dat") }
                }
            }
            overlayAndFilterPriority()
        }

        private fun providerSources(snapshot: MinecraftFontSnapshot): List<String> {
            val field = MinecraftFontSnapshot::class.java.getDeclaredField("fonts").apply { isAccessible = true }
            val fonts = checkNotNull(field.get(snapshot) as? Map<*, *>)
            val providers = checkNotNull(fonts[ResourceId("minecraft", "default")] as? List<*>)
            return providers.map { provider ->
                val entry = checkNotNull(provider)
                checkNotNull(entry.javaClass.getMethod("getSource").invoke(entry) as? String)
            }
        }

        private fun overlayAndFilterPriority() {
            val files = JvmPerformanceInputs.read(IndexedFontAssetFiles.prepare(root.resolve("overlay-inputs")))
            IndexedFontAssetFiles.open(IndexedFontInput.Shared128, files).use { fixture ->
                val overlay = Files.createDirectories(fixture.directory.resolve("selected/assets/minecraft/font")).resolve("default.json")
                Files.writeString(overlay, """{"providers":[{"type":"space","advances":{"A":17},"filter":{"uniform":true}},{"type":"space","advances":{"A":19}}]}""")
                val metadata = fixture.directory.resolve("pack.mcmeta")
                Files.writeString(metadata, """{"overlays":{"entries":[{"directory":"selected","formats":84}]}}""")
                val snapshot = fixture.loadExample()
                check(fixture.output(snapshot).glyphs.first().advance == 19f)
                val uniform = MinecraftFontSnapshot.load(listOf(fixture.source(), MinecraftArchiveFontAssetSource(fixture.archive), MinecraftDirectoryFontAssetSource(fixture.directory)), IndexedFontAssetFiles.compatibility, IndexedFontAssetFiles.options.copy(uniform = true), limits)
                check(fixture.output(uniform).glyphs.first().advance == 17f)
                check(providerSources(snapshot).take(2) == listOf(fixture.directory.toString(), fixture.directory.toString()))
                Files.writeString(metadata, """{"filter":{"block":[{"namespace":"minecraft","path":"font/default\\.json"}]}}""")
                check(fixture.output(fixture.loadExample()).glyphs.take(3).map { it.advance } == listOf(13f, 6f, 5f))
                Files.writeString(metadata, """{"filter":{"block":[{"namespace":"["}]}}""")
                val diagnostics = fixture.loadExample().diagnostics
                check(diagnostics.size == 1 && diagnostics.single().kind == MinecraftFontDiagnostic.Kind.PackMetadataFailure)
                check(diagnostics.single().source == fixture.directory.toString())
            }
        }

        private fun snapshotRetirement() {
            val (snapshot, references, reads) = detachedSnapshot()
            repeat(20) {
                if (references.all { reference -> reference.get() == null }) return@repeat
                System.gc()
                Thread.sleep(10)
            }
            check(references.all { reference -> reference.get() == null }) { "Detached snapshot retained its retired source or captured index" }
            check(reads == 2 && snapshot.fontIds == setOf(ResourceId("minecraft", "default")))
            requireClosedIndex()
            val fixtureFiles = JvmPerformanceInputs.read(IndexedFontAssetFiles.prepare(root.resolve("retirement-inputs")))
            IndexedFontAssetFiles.open(IndexedFontInput.Distinct1, fixtureFiles).use { fixture ->
                val before = fixture.loadExample()
                val output = fixture.output(before)
                Files.writeString(fixture.index, "invalid JSON")
                Files.writeString(fixture.directory.resolve(IndexedFontAssetFiles.defaultPath), "invalid JSON")
                check(output == fixture.output(before))
            }
        }

        private fun detachedSnapshot(): Triple<MinecraftFontSnapshot, List<WeakReference<*>>, Int> {
            var retired = false
            var reads = 0
            val delegate = source(records(listOf("minecraft/font/default.json" to valid)))
            writeObject(valid, """{"providers":[{"type":"space","advances":{"A":7}}]}""".toByteArray())
            val source = object : MinecraftFontAssetSource {
                override val name = "retiring"
                override fun paths(): Set<String> {
                    check(retired.not())
                    return delegate.paths()
                }
                override fun read(path: String): ByteArray? {
                    check(retired.not())
                    reads++
                    return delegate.read(path)
                }
            }
            val snapshot = MinecraftFontSnapshot.load(listOf(source), IndexedFontAssetFiles.compatibility)
            retired = true
            val entries = MinecraftIndexedFontAssetSource::class.java.getDeclaredField("entries").apply { isAccessible = true }.get(delegate)
            return Triple(snapshot, listOf(WeakReference(source), WeakReference(delegate), WeakReference(entries)), reads)
        }
    }

    private enum class Control {
        Empty,
        ValidHashes,
        ValidPositions,
        InvalidAscii,
        Uppercase,
        Lengths,
        Whitespace,
        NonAscii,
        Surrogates,
        Escapes,
        HashShapes,
        DocumentShapes,
        InvalidPositions,
        FailFastOrder,
        Aliases,
        PathHashPrecedence,
        Paths,
        PathCeilings,
        ByteCeilings,
        EntryCeilings,
        JsonCeilings,
        Constructors,
        ObjectResolution,
        SymlinksAndIo,
        StreamClosure,
        FreshBytes,
        CapturedIndex,
        IndependentOwners,
        SnapshotPriority,
        SnapshotRetirement,
        CommonQualification,
        ZeroWorkAndPortableText,
    }
}
