package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionBinding
import dev.s7a.strata.projection.ProjectionScope
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.remote.RemoteTextCodec
import dev.s7a.strata.text.UiText

/**
 * Invocation-local deterministic endpoints without retaining actions, bindings, callback scopes or output history.
 * Real source identities, generations and release are checked separately by the retained remote owner workloads.
 */
internal class ProjectionFieldScope : ProjectionScope {
    private var endpoint = 0L

    /**
     * Restarts the bounded endpoint positions before each projection, outside any retained session protocol.
     */
    fun reset() {
        endpoint = 0
    }

    override fun action(action: ProjectionAction<*>, key: ProjectionValue): Long = ++endpoint

    override fun <T : Any> binding(binding: ProjectionBinding<T>): ProjectionValue =
        ProjectionValue.Sequence(listOf(ProjectionValue.Integer(++endpoint), ProjectionValue.Integer(1), ProjectionValue.Integer(0), binding.encode(binding.read()), ProjectionValue.Integer(++endpoint)))

    override fun image(image: DrawImage): ProjectionValue = error("This fixture has no images")

    override fun text(text: UiText): ProjectionValue = RemoteTextCodec.encode(text)

    override fun requireType(type: ProjectionType): Unit = Unit
}
