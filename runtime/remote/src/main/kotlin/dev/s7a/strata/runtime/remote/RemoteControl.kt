package dev.s7a.strata.runtime.remote

import dev.s7a.strata.element.Element
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import kotlin.reflect.KClass

/**
 * Typed standard control adapter retaining one native editing state per server source identity.
 * The registry owns the type token; the screen store owns instances and their optimistic ordering metadata.
 */
internal class RemoteControl<S : Any, T : Any>(
    private val stateClass: KClass<S>,
    private val type: ProjectionType,
    private val decode: (ProjectionValue) -> T,
    private val encode: (T) -> ProjectionValue,
    private val read: (S) -> T,
    private val write: (S, T) -> Unit,
) {
    private val key = RemoteStateKey(Editing::class)

    /**
     * Validates a binding value and prepares its native owner before any declaration factory runs.
     */
    fun prepare(
        snapshot: RemoteBindingSnapshot,
        create: (T) -> S,
        context: RemotePreparationContext,
    ): S {
        val initial = decode(snapshot.value)
        var prepared: S? = null
        context.states.prepare(snapshot.identity, key, {
            val state = create(initial)
            val editor = RemoteEditingState(state, type, snapshot, decode, encode, read, write)
            Editing(state, editor, editor::reconcile)
        }, {
            it.reconcile(snapshot)
            prepared = stateClass.java.cast(it.state)
        })
        return checkNotNull(prepared)
    }

    /**
     * Reads the native state prepared for this source identity without allocating during evaluation.
     */
    fun get(
        snapshot: RemoteBindingSnapshot,
        states: RemoteClientStates,
    ): S = stateClass.java.cast(states.get(snapshot.identity, key).state)

    /**
     * Returns decoded properties with separate preparation and declaration callbacks.
     * Creating a native state is deferred until the complete incoming tree has passed decoding.
     */
    fun specification(
        snapshot: RemoteBindingSnapshot,
        create: (T) -> S,
        render: (S, RemoteElementContext) -> Element,
        prepareState: (S, RemotePreparationContext) -> Unit = { _, _ -> },
    ): Specification {
        decode(snapshot.value)
        return Specification(
            prepare = { context ->
                prepareState(prepare(snapshot, create, context), context)
            },
            render = { context -> render(get(snapshot, context.states), context) },
        )
    }

    /**
     * Prepared control properties containing trusted local callbacks only.
     */
    class Specification(
        val prepare: (RemotePreparationContext) -> Unit,
        val render: (RemoteElementContext) -> Element,
    )

    /**
     * Erasure is checked through the control's native state class before the renderer receives a value.
     */
    private class Editing(
        val state: Any,
        private val editable: RemoteEditableValue,
        val reconcile: (RemoteBindingSnapshot) -> Unit,
    ) : RemoteEditableValue {
        override fun flushEdits(actions: RemoteClientActions) = editable.flushEdits(actions)
    }
}
