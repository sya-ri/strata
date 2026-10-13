@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Requires terminal edit callbacks to close every public flush entry point without publishing another action.
 * Both the direct adapter flush and normal action allocator release the complete session and notify its peer once.
 */
internal class RemoteClientFlushLifetimeTest {
    @Test
    fun closingDuringEitherFlushEntryPointTerminatesTheSessionAndStopsSiblingPolling() {
        EntryPoint.entries.forEach { verifyCutoff(it, null) }
    }

    @Test
    fun anOriginalCallbackFailureAfterCloseStaysPrimaryAtEitherFlushEntryPoint() {
        EntryPoint.entries.forEach { verifyCutoff(it, IllegalArgumentException("edit failed after close")) }
    }

    private fun verifyCutoff(
        entryPoint: EntryPoint,
        failure: IllegalArgumentException?,
    ) {
        val released = mutableListOf<Long>()
        val outgoing = mutableListOf<RemoteMessage>()
        var firstPolls = 0
        var siblingPolls = 0
        var actions: RemoteClientActions? = null
        val registry = RemoteRegistry()
        val key = RemoteStateKey(RemoteEditableValue::class)
        registry.element(TYPE, { it }, { _, context ->
            context.states.prepare(1, key, {
                RemoteEditableValue {
                    firstPolls++
                    context.states.close()
                    failure?.let { throw it }
                }
            }, {}, { released.add(1) })
            context.states.prepare(2, key, { RemoteEditableValue { siblingPolls++ } }, {}, { released.add(2) })
        }) { _, context ->
            actions = context.actions
            evaluateComponentTree { Spacer(context.modifier, context.key) }
        }
        val tree = RemoteTree(1, listOf(RemoteNode(RemoteDeclaration(1, TYPE, ProjectionValue.Absent), emptyList(), emptyList())))
        val client = RemoteClientSession(RemoteMessage.Snapshot(1, 1, ProjectionValue.Absent, tree), registry, send = outgoing::add)
        val content = client.definition(UiText.Literal("Callback cutoff")).transfer().content
        val host = createRuntimeUiSession { evaluateComponentTree(content) }
        try {
            host.attach()
            val caught =
                assertThrows(RuntimeException::class.java) {
                    when (entryPoint) {
                        EntryPoint.Direct -> client.flushEdits()
                        EntryPoint.PreAction -> checkNotNull(actions).send(1, TYPE, ProjectionValue.Absent)
                    }
                }
            if (failure != null) assertSame(failure, caught)
            assertEquals(1, firstPolls)
            assertEquals(0, siblingPolls)
            assertEquals(listOf(1L, 2L), released)
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.InvalidMessage), client.status)
            assertEquals(listOf(RemoteMessage.Applied(1, 1), RemoteMessage.Close(1, RemoteFailure.InvalidMessage)), outgoing)
            assertEquals(0, client.nodeCount)
            client.close()
            assertEquals(listOf(1L, 2L), released)
            assertEquals(2, outgoing.size)
        } finally {
            host.close()
            client.close()
        }
    }

    private enum class EntryPoint { Direct, PreAction }

    private companion object {
        val TYPE = ProjectionType(ResourceId("test", "flush_lifetime"))
    }
}
