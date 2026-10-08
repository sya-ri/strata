@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadlessRegion
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Checks independent effect admission without changing overlapping CPU composition or retaining proof history.
 */
internal class FabricMinecraftSamplingCompositionTest {
    private val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0x807195B3.toInt(), 0, -1))
    private val sample = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(1f, 1f, 5f, 5f), ArgbColor(0xFF00FFFF.toInt()), alphaCutoff = 1f)

    @Test
    fun disjointMasksOverOpaqueFillsRetainExactGpuPresentation() {
        val background = DrawCommand.FillRectangle(IntRect(0, 0, 256, 144), ArgbColor(0xFF7195B3.toInt()))
        val masks = List(40) { index -> sample.copy(destination = FloatRect(index % 8 * 16f, index / 8 * 12f, index % 8 * 16f + 12f, index / 8 * 12f + 8f)) }
        val layers = partitionFabricMinecraftFrame(listOf(background) + masks, IntSize(256, 144), exactSampling = true)
        assertEquals(40, layers.count { it is FabricMinecraftFrameLayer.Sampled })
    }

    @Test
    fun earlierAndLaterOverlappingLayersKeepTheOriginalCpuRun() {
        val rectangle = DrawCommand.FillRectangle(IntRect(2, 2, 6, 6), ArgbColor(0x807195B3.toInt()))
        val shadow = sample.copy(destination = FloatRect(2f, 2f, 6f, 6f), tint = ArgbColor(0xFF123456.toInt()))
        val blit = DrawCommand.BlitImage(image, IntRect(0, 0, 2, 2), IntRect(2, 2, 6, 6))
        val pixels = DrawCommand.BlitImagePixels(image, IntRect(0, 0, 2, 2), IntRect(2, 2, 6, 6))
        for (selected in listOf(sample, sample.copy(tint = ArgbColor(-1)))) {
            for (other in listOf(rectangle, shadow, blit, pixels, selected)) {
                for (commands in listOf(listOf(other, selected), listOf(selected, other))) {
                    val layers = partitionFabricMinecraftFrame(commands, IntSize(8, 8), exactSampling = true)
                    assertTrue(layers.all { it is FabricMinecraftFrameLayer.Portable })
                    assertEquals(commands, (layers.single() as FabricMinecraftFrameLayer.Portable).commands)
                }
            }
        }
    }

    @Test
    fun distantTranslucentCompositionCannotBeSplitByANewNativeBarrier() {
        val background = DrawCommand.FillRectangle(IntRect(0, 0, 8, 8), ArgbColor(0xFF7195B3.toInt()))
        val distant = sample.copy(destination = FloatRect(10f, 10f, 14f, 14f))
        val first = DrawCommand.FillRectangle(IntRect(1, 1, 5, 5), ArgbColor(0x80472853.toInt()))
        val second = first.copy(color = ArgbColor(0x80007BBC.toInt()))
        val bounds = IntRect(0, 0, 16, 16)
        val original = rasterizeHeadlessRegion(listOf(background, distant, first, second), bounds, 1)
        val isolated = rasterizeHeadlessRegion(listOf(first, second), first.bounds, 1)
        val image = createDrawImage(isolated.size, isolated.copyArgb())
        val split = DrawCommand.BlitImage(image, IntRect(0, 0, 4, 4), first.bounds)
        val separated = rasterizeHeadlessRegion(listOf(background, distant, split), bounds, 1)
        assertEquals(0xFF2E6DA0.toInt(), original.argbAt(2, 2))
        assertEquals(0xFF2E6C9F.toInt(), separated.argbAt(2, 2))
        for (commands in listOf(listOf(background, distant, first, second), listOf(background, first, distant, second))) {
            val layers = partitionFabricMinecraftFrame(commands, IntSize(16, 16), exactSampling = true)
            assertTrue(layers.all { it is FabricMinecraftFrameLayer.Portable })
            assertEquals(commands, (layers.single() as FabricMinecraftFrameLayer.Portable).commands)
        }
    }

    @Test
    fun clipsAndNativeBarriersDoNotHideOverlappingComposition() {
        val platform = DrawCommand.Platform(TestPlatform, IntRect(2, 2, 6, 6))
        assertFalse(FabricMinecraftSamplingComposition(listOf(sample, platform)).admits(0, sample))
        val touching = platform.copy(bounds = IntRect(5, 1, 7, 5))
        assertFalse(FabricMinecraftSamplingComposition(listOf(sample, touching)).admits(0, sample))
        val commands =
            listOf(
                DrawCommand.PushClip(IntRect(0, 0, 4, 4)),
                sample.copy(tint = ArgbColor(0xFF123456.toInt())),
                DrawCommand.PopClip,
                touching,
                DrawCommand.PushFractionalClip(FloatRect(4f, 4f, 8f, 8f)),
                sample,
                DrawCommand.PopClip,
            )
        assertFalse(FabricMinecraftSamplingComposition(commands).admits(5, sample))
    }

    @Test
    fun boundedProofExhaustionFallsBackAndANewFrameStartsWithoutHistory() {
        val commands = List(8193) { DrawCommand.FillRectangle(IntRect(0, 0, 8, 8), ArgbColor(-1)) } + sample
        val proof = FabricMinecraftSamplingComposition(commands)
        assertFalse(proof.admits(commands.lastIndex, sample))
        assertFalse(proof.admits(commands.lastIndex, sample))
        assertTrue(FabricMinecraftSamplingComposition(listOf(sample)).admits(0, sample))
    }

    @Test
    fun manyOpaqueFillsAreClassifiedOnceWithinTheCompleteWorkBound() {
        val fills = List(4096) { DrawCommand.FillRectangle(IntRect(0, 0, 8, 8), ArgbColor(-1)) }
        val masks = List(32) { index -> sample.copy(destination = FloatRect(index * 8f, 0f, index * 8f + 4f, 4f)) }
        val commands = fills + masks
        val proof = FabricMinecraftSamplingComposition(commands)
        masks.forEachIndexed { index, command -> assertTrue(proof.admits(fills.size + index, command)) }
        assertEquals(4128, proof.classificationVisits)
        assertEquals(32, proof.admissionVisits)
        assertEquals(1024, proof.maskVisits)
        assertEquals(992, proof.geometryChecks)
        assertEquals(5184, proof.workCount)
        assertEquals(32, proof.retainedOccurrenceCount)
        assertEquals(32, partitionFabricMinecraftFrame(commands, IntSize(256, 144), exactSampling = true).count { it is FabricMinecraftFrameLayer.Sampled })
    }

    @Test
    fun allCommandKindsAgreeWithAnIndependentConservativeFullListOracle() {
        val others =
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 8, 8), ArgbColor(-1)),
                DrawCommand.FillRectangle(IntRect(20, 20, 24, 24), ArgbColor(0x80472853.toInt())),
                DrawCommand.FillRectangle(IntRect(20, 20, 24, 24), ArgbColor(0)),
                DrawCommand.BlitImage(image, IntRect(0, 0, 2, 2), IntRect(20, 20, 24, 24)),
                DrawCommand.BlitImagePixels(image, IntRect(0, 0, 2, 2), IntRect(20, 20, 24, 24)),
                DrawCommand.Platform(TestPlatform, IntRect(20, 20, 24, 24)),
                sample.copy(destination = FloatRect(20f, 20f, 24f, 24f), tint = ArgbColor(-1), alphaCutoff = 0f),
                sample.copy(destination = FloatRect(20f, 20f, 24f, 24f), tint = ArgbColor(0xFF123456.toInt())),
                sample.copy(destination = FloatRect(20f, 20f, 24f, 24f), alphaCutoff = 0.5f),
                sample.copy(tint = ArgbColor(0x00123456)),
                sample.copy(destination = FloatRect(5f, 1f, 9f, 5f)),
                sample.copy(destination = FloatRect(4f, 1f, 8f, 5f)),
                sample,
                sample.copy(),
            )
        others.forEach { other ->
            listOf(listOf(other, sample), listOf(sample, other)).forEach { commands ->
                commands.forEachIndexed { occurrence, command ->
                    if (command is DrawCommand.SampledImage && command.alphaCutoff == 1f && exactOpaqueTint(command.tint)) {
                        assertEquals(conservativeOracle(commands, occurrence, command), FabricMinecraftSamplingComposition(commands).admits(occurrence, command))
                    }
                }
            }
        }
        val clipped = listOf(DrawCommand.PushClip(IntRect(0, 0, 1, 1)), sample, DrawCommand.PopClip, DrawCommand.PushFractionalClip(FloatRect(20f, 20f, 21f, 21f)), sample.copy(), DrawCommand.PopClip)
        assertFalse(FabricMinecraftSamplingComposition(clipped).admits(1, sample))
        assertEquals(conservativeOracle(clipped, 1, sample), FabricMinecraftSamplingComposition(clipped).admits(1, sample))
    }

    @Test
    fun touchingBoundsAndRepeatedObjectsUseOccurrenceIdentity() {
        val right = sample.copy(destination = FloatRect(5f, 1f, 9f, 5f))
        val below = sample.copy(destination = FloatRect(1f, 5f, 5f, 9f))
        val touching = listOf(sample, right, below)
        val proof = FabricMinecraftSamplingComposition(touching)
        touching.forEachIndexed { occurrence, command -> assertTrue(proof.admits(occurrence, command)) }
        val repeated = listOf(sample, sample, right)
        val repeatedProof = FabricMinecraftSamplingComposition(repeated)
        assertFalse(repeatedProof.admits(0, sample))
        assertFalse(repeatedProof.admits(1, sample))
        assertTrue(repeatedProof.admits(2, right))
        assertEquals(3, repeatedProof.classificationVisits)
        assertEquals(3, repeatedProof.retainedOccurrenceCount)
        assertFalse(repeatedProof.admits(0, sample.copy()))
    }

    @Test
    fun classificationIsLazyAndAnEarlyGlobalBlockerIsNotRescanned() {
        var reads = 0
        val opaque = DrawCommand.FillRectangle(IntRect(0, 0, 8, 8), ArgbColor(-1))
        val blocker = opaque.copy(color = ArgbColor(0x80472853.toInt()))
        val commands =
            object : AbstractList<DrawCommand>() {
                override val size: Int = 100_002

                override fun get(index: Int): DrawCommand {
                    reads += 1
                    return when (index) {
                        0 -> blocker
                        1 -> sample
                        else -> opaque
                    }
                }
            }
        val proof = FabricMinecraftSamplingComposition(commands)
        assertEquals(0, reads)
        assertEquals(0, proof.workCount)
        assertEquals(0, proof.retainedOccurrenceCount)
        assertFalse(proof.admits(1, sample))
        assertEquals(2, reads)
        assertEquals(1, proof.classificationVisits)
        assertFalse(proof.admits(1, sample))
        assertEquals(2, reads)
        assertEquals(0, proof.retainedOccurrenceCount)
        val independent = FabricMinecraftSamplingComposition(listOf(sample))
        assertTrue(independent.admits(0, sample))
        assertEquals(1, independent.classificationVisits)
    }

    @Test
    fun admissionClassificationAndGeometryShareTheSameExhaustionBoundary() {
        val opaque = DrawCommand.FillRectangle(IntRect(0, 0, 8, 8), ArgbColor(-1))
        listOf(8188 to true, 8189 to true, 8190 to false).forEach { (fills, expected) ->
            val commands = List(fills) { opaque } + sample
            val proof = FabricMinecraftSamplingComposition(commands)
            assertEquals(expected, proof.admits(commands.lastIndex, sample))
            assertTrue(proof.workCount <= 8192)
            assertEquals(proof.admissionVisits + proof.classificationVisits + proof.maskVisits, proof.workCount)
            if (expected.not()) assertEquals(0, proof.retainedOccurrenceCount)
        }
        val masks = List(4095) { index -> sample.copy(destination = FloatRect(index * 8f, 0f, index * 8f + 4f, 4f)) }
        val proof = FabricMinecraftSamplingComposition(masks)
        assertTrue(proof.admits(0, masks.first()))
        assertEquals(8191, proof.workCount)
        assertEquals(4095, proof.retainedOccurrenceCount)
        assertFalse(proof.admits(1, masks[1]))
        assertEquals(8192, proof.workCount)
        assertEquals(0, proof.retainedOccurrenceCount)
        assertFalse(proof.admits(0, masks.first()))
        assertEquals(8192, proof.workCount)
    }

    @Test
    fun failedClassificationReleasesPartialBoundsAndFailsClosed() {
        val failure = IllegalStateException("classification failure")
        var reads = 0
        val other = sample.copy(destination = FloatRect(20f, 20f, 24f, 24f))
        val commands =
            object : AbstractList<DrawCommand>() {
                override val size: Int = 3

                override fun get(index: Int): DrawCommand {
                    reads += 1
                    if (index == 2) throw failure
                    return if (index == 0) sample else other
                }
            }
        val proof = FabricMinecraftSamplingComposition(commands)
        assertSame(failure, assertThrows(IllegalStateException::class.java) { proof.admits(0, sample) })
        assertEquals(0, proof.retainedOccurrenceCount)
        val failedReads = reads
        assertFalse(proof.admits(0, sample))
        assertEquals(failedReads, reads)
        assertEquals(0, proof.retainedOccurrenceCount)
        assertTrue(FabricMinecraftSamplingComposition(listOf(sample)).admits(0, sample))
    }

    @Test
    fun retainedMaskRecordsContainNoImagesCommandsOrNativeOwnership() {
        val proof = FabricMinecraftSamplingComposition(listOf(sample))
        assertTrue(proof.admits(0, sample))
        val field = proof.javaClass.getDeclaredField("masks")
        assertTrue(field.trySetAccessible())
        val records = field.get(proof) as List<*>
        assertEquals(1, records.size)
        val record = checkNotNull(records.single())
        val fields = record.javaClass.declaredFields.filter { it.isSynthetic.not() && Modifier.isStatic(it.modifiers).not() }
        assertEquals(setOf(Int::class.javaPrimitiveType, FloatRect::class.java), fields.map { it.type }.toSet())
    }

    @Test
    fun additionalSafeAdmissionsStillResolveEachCurrentSourceCapacityDecision() {
        val images = List(32) { createDrawImage(image.size, image.copyArgb()) }
        val fills = List(4096) { DrawCommand.FillRectangle(IntRect(0, 0, 1, 1), ArgbColor(-1)) }
        val masks = images.mapIndexed { index, source -> sample.copy(image = source, destination = FloatRect(index * 8f, 0f, index * 8f + 4f, 4f)) }
        val inputs = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(fills + masks, IntSize(256, 144), exactSampling = true), 1)
        assertEquals(32, inputs.sampled.size)
        assertEquals(32, inputs.layers.count { it is FabricMinecraftFrameLayer.Sampled })
        assertEquals(0L, inputs.capacitySampledImages)
        val oneAvailable = inputs.resolve({ it === images.first() }) { true }
        assertEquals(1, oneAvailable.layers.count { it is FabricMinecraftFrameLayer.Sampled })
        assertEquals(31L, oneAvailable.capacitySampledImages)
        assertEquals(0L, oneAvailable.ineligibleSampledImages)
        assertSame(inputs, inputs.resolve({ true }) { true })
        assertEquals(0L, inputs.capacitySampledImages)
    }

    @Test
    fun aRejectedCandidateDoesNotClassifyOrPoisonTheValidOccurrence() {
        val commands = listOf(sample)
        val proof = FabricMinecraftSamplingComposition(commands)
        assertFalse(proof.admits(-1, sample))
        assertFalse(proof.admits(0, sample.copy()))
        assertFalse(proof.admits(0, sample.copy(alphaCutoff = 0.5f)))
        assertEquals(0, proof.classificationVisits)
        assertEquals(0, proof.retainedOccurrenceCount)
        assertTrue(proof.admits(0, sample))
        assertEquals(1, proof.classificationVisits)
        assertEquals(4, proof.admissionVisits)
        assertEquals(6, proof.workCount)
    }

    private fun conservativeOracle(
        commands: List<DrawCommand>,
        occurrence: Int,
        candidate: DrawCommand.SampledImage,
    ): Boolean {
        for (index in commands.indices) {
            if (index == occurrence) continue
            when (val command = commands[index]) {
                is DrawCommand.FillRectangle -> {
                    if (command.color.value ushr 24 != 255) return false
                }

                is DrawCommand.BlitImage, is DrawCommand.BlitImagePixels, is DrawCommand.Platform -> {
                    return false
                }

                is DrawCommand.PushClip, is DrawCommand.PushFractionalClip, DrawCommand.PopClip -> {
                    Unit
                }

                is DrawCommand.SampledImage -> {
                    if (command.tint.value ushr 24 == 0) continue
                    if (command.alphaCutoff != 1f || exactOpaqueTint(command.tint).not()) return false
                    val left = maxOf(candidate.destination.left, command.destination.left)
                    val top = maxOf(candidate.destination.top, command.destination.top)
                    val right = minOf(candidate.destination.right, command.destination.right)
                    val bottom = minOf(candidate.destination.bottom, command.destination.bottom)
                    if (left < right && top < bottom) return false
                }
            }
        }
        return true
    }

    private fun exactOpaqueTint(tint: ArgbColor): Boolean = tint.value ushr 24 == 255 && listOf(tint.value ushr 16 and 255, tint.value ushr 8 and 255, tint.value and 255).all { it == 0 || it == 255 }

    private data object TestPlatform : PlatformDrawCommand
}
