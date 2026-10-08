package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.fabric.loadMinecraftUiImage
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition

/**
 * Measures actual public Fabric resource resolution and presentation with fixed packaged PNGs and unchanged geometry.
 * Bridge cases call the same loader as overflow resolution, without claiming to exhaust this screen's host admission.
 * Pinned cases exercise the unmodified public resource-image host path as negative controls.
 */
internal class MinecraftResourceDecodePerformanceScene(
    private val case: MinecraftSampledPerformanceCase,
) {
    private val revision = mutableStateOf(0)
    private val mode = checkNotNull(case.resource)

    /**
     * Invalidates only declaration preparation, outside the ordinary declarative frame phases.
     */
    fun update() {
        revision.value++
    }

    /**
     * Resolves current resources and draws the last detached snapshot through actual native sampled-image presentation.
     */
    fun definition(): UiDefinition =
        UiDefinition(case.name) {
            val index = revision.value
            val source =
                if (mode == MinecraftSampledPerformanceCase.Resource.Pinned) {
                    ImageSource.Resource(asset(0))
                } else {
                    val id = asset(if (mode == MinecraftSampledPerformanceCase.Resource.Cold) index % 8 else 0)
                    var image = loadMinecraftUiImage(id)
                    repeat(if (mode == MinecraftSampledPerformanceCase.Resource.Hot100) 99 else 0) {
                        val repeated = loadMinecraftUiImage(id)
                        check(repeated !== image && repeated.size == image.size)
                        image = repeated
                    }
                    ImageSource.Pixels(image)
                }
            Image(source, size = IntSize(16, 16))
        }

    private fun asset(index: Int): ResourceId = ResourceId("strata_test", "textures/gui/resource_decode/${case.resolution}_$index.png")
}
