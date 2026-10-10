@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.fixture

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.quality.fixture.EmptyChildProbe.Kind
import dev.s7a.strata.quality.fixture.EmptyChildProbe.Stage
import dev.s7a.strata.quality.fixture.EmptyChildWorkload.Operation
import dev.s7a.strata.quality.fixture.EmptyChildWorkload.Payload
import dev.s7a.strata.runtime.TreeState
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderNodeKind
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText

/**
 * Independent public-behavior qualification shared verbatim by JVM JMH preflight and portable JVM/JavaScript tests.
 * Each control owns its tree, assertions and work observations; elapsed time and guard branch counts prove none of them.
 */
@Suppress("TooManyFunctions", "LargeClass")
public object EmptyChildControls {
    /**
     * All 32 frozen acceptance groups, with one independently invoked control for each group.
     */
    public enum class Control {
        FreshEquivalentLeaf, ChangedLeafPayload, SameDescription, ColdCreation,
        EmptyToOne, OneToEmpty, NonemptyToNonempty, KeyedInsertion, KeyedRemoval, KeyedReorder,
        PositionalReplacement, KeyedTypeReplacement, InvalidDuplicateKeys, LocalValidationFailure,
        ComponentUpdateFailure, ModifierUpdateFailure, CreateFailure, AttachFailure, DetachFailure, DisposeFailure,
        ModifierIdentity, EffectiveAncestry, DynamicEmptyOutput, DeferredEmptyOutput, DynamicChangedOutput,
        LocalizedUpdates, FrameIdentity, PixelsAndGeometry, InputAndSemantics, MonitoringParity,
        OwnerIsolation, TerminalRetention,
    }

    /**
     * Executes every independent group and all 60 fixed workload cases before sampling.
     */
    public fun verify() {
        Control.entries.forEach(::verify)
        verifyInventory()
    }

    /**
     * Runs exactly one independently identified acceptance group.
     */
    public fun verify(control: Control) {
        when (control) {
            Control.FreshEquivalentLeaf -> freshLeaf()
            Control.ChangedLeafPayload -> changedMasks()
            Control.SameDescription -> sameDescription()
            Control.ColdCreation -> coldCreation()
            Control.EmptyToOne -> transition(Payload.EmptyToOne)
            Control.OneToEmpty -> transition(Payload.OneToEmpty)
            Control.NonemptyToNonempty -> transition(Payload.NonemptyToNonempty)
            Control.KeyedInsertion -> keyedInsertion()
            Control.KeyedRemoval -> keyedRemoval()
            Control.KeyedReorder -> reorder(false)
            Control.PositionalReplacement -> replacement(false)
            Control.KeyedTypeReplacement -> replacement(true)
            Control.InvalidDuplicateKeys -> validation(true)
            Control.LocalValidationFailure -> validation(false)
            Control.ComponentUpdateFailure -> failure(Stage.Update)
            Control.ModifierUpdateFailure -> failure(Stage.ModifierUpdate)
            Control.CreateFailure -> failure(Stage.Create)
            Control.AttachFailure -> failure(Stage.Attach)
            Control.DetachFailure -> failure(Stage.Detach)
            Control.DisposeFailure -> failure(Stage.Dispose)
            Control.ModifierIdentity -> modifierIdentity()
            Control.EffectiveAncestry -> reorder(true)
            Control.DynamicEmptyOutput -> dynamicEmpty()
            Control.DeferredEmptyOutput -> deferredEmpty()
            Control.DynamicChangedOutput -> dynamicOscillation()
            Control.LocalizedUpdates -> localized()
            Control.FrameIdentity -> frameIdentity()
            Control.PixelsAndGeometry -> EmptyChildPresentationControl.verify()
            Control.InputAndSemantics -> EmptyChildInputControl.verify()
            Control.MonitoringParity -> monitoringParity()
            Control.OwnerIsolation -> ownerIsolation()
            Control.TerminalRetention -> terminal()
        }
    }

    /**
     * Qualifies the complete frozen inventory and independently derives target misses and descendant eligibility.
     */
    public fun verifyInventory() {
        check(EmptyChildWorkload.entries.size == 30)
        EmptyChildWorkload.entries.forEach { workload ->
            listOf(false, true).forEach { enabled ->
                EmptyChildFixture(workload, enabled).use { fixture ->
                    val before = fixture.probe.nodes.toList()
                    val result = fixture.execute()
                    val n = workload.count
                    val clean = workload.operation == Operation.CleanSessionFrame
                    val same = workload.operation == Operation.SameDescriptionTreeUpdate
                    val children = workload.payload == Payload.NonemptyToNonempty
                    val adding = workload.payload == Payload.EmptyToOne
                    val removing = workload.payload == Payload.OneToEmpty
                    val expectedUpdates = if (clean || same) 0 else n + 1 + if (children) n else 0
                    val expectedValidations = if (clean) 0 else n + 1 + if (children || adding) n else 0
                    check(fixture.probe.count(Stage.Update) == expectedUpdates) { "Wrong component work: $workload/$enabled" }
                    check(fixture.probe.count(Stage.Validate) == expectedValidations) { "Wrong complete validation: $workload/$enabled" }
                    check(fixture.probe.count(Stage.ModifierUpdate) == if (clean || same) 0 else n)
                    check(fixture.probe.count(Stage.ModifierValidate) == if (clean) 0 else n)
                    val eligible = if (clean || same || adding || removing) 0 else n
                    val misses = if (clean || same) 0 else if (adding || removing || children) n + 1 else 1
                    check(fixture.probe.count(Stage.EligibleUpdate) == eligible)
                    check(fixture.probe.count(Stage.NonemptyUpdate) == misses)
                    check(fixture.probe.count(Stage.TargetEligibleUpdate) == if (clean || same || adding || removing || children) 0 else n)
                    check(fixture.probe.count(Stage.TargetMissUpdate) == if (clean || same || (adding || removing || children).not()) 0 else n)
                    check(fixture.probe.count(Stage.DescendantEligibleUpdate) == if (children && (clean || same).not()) n else 0)
                    check(fixture.probe.count(Stage.DescendantMissUpdate) == 0)
                    check(fixture.probe.count(Stage.SurroundingMissUpdate) == if (clean || same) 0 else 1)
                    check(fixture.probe.count(Stage.Create) == if (adding && (clean || same).not()) n else 0)
                    check(fixture.probe.count(Stage.Attach) == if (adding && (clean || same).not()) n else 0)
                    check(fixture.probe.count(Stage.Detach) == if (removing && (clean || same).not()) n else 0)
                    check(fixture.probe.count(Stage.Dispose) == if (removing && (clean || same).not()) n else 0)
                    check(before.filter { it.id < n }.all { old -> fixture.probe.nodes.any { it === old } && old.disposed.not() })
                    if (clean) check(result === fixture.initialFrame)
                    fixture.monitor?.snapshot()?.let { snapshot ->
                        check(snapshot.overflowed.not())
                        check((snapshot.counts[UiRenderMetric.NodeUpdate] ?: 0L) == (expectedUpdates + if (clean || same) 0 else n).toLong())
                        check((snapshot.counts[UiRenderMetric.NodeCreate] ?: 0L) == if (adding && (clean || same).not()) n.toLong() else 0L)
                        check((snapshot.counts[UiRenderMetric.NodeDispose] ?: 0L) == if (removing && (clean || same).not()) n.toLong() else 0L)
                    }
                    fixture.settle()
                }
            }
        }
    }

    private fun freshLeaf() {
        val probe = EmptyChildProbe()
        UiTree().use { tree ->
            tree.update(probe.element(1, modifier = probe.modifier(1)))
            val node = probe.nodes.single()
            probe.checkpoint()
            tree.update(probe.element(1, modifier = probe.modifier(1)))
            check(probe.count(Stage.Validate) == 1 && probe.count(Stage.Update) == 1)
            check(probe.count(Stage.ModifierValidate) == 1 && probe.count(Stage.ModifierUpdate) == 1)
            check(probe.nodes.single() === node && probe.events.isEmpty())
        }
        val version = mutableStateOf(false)
        createRuntimeUiSession { if (version.value) probe.element(2) else probe.element(2) }.use { session ->
            session.attach()
            val previous = session.projectDeclarations { it.identity to it.element }
            version.value = true
            val current = session.projectDeclarations { it.identity to it.element }
            check(previous.first == current.first && previous.second !== current.second)
        }
    }

    private fun changedMasks() {
        listOf(DirtyPhase.Measure, DirtyPhase.Paint, DirtyPhase.Semantics).forEach { phase ->
            val probe = EmptyChildProbe()
            UiTree().use { tree ->
                tree.update(probe.element(1))
                settle(tree)
                val monitor = tree.startRenderMonitoring()
                probe.checkpoint()
                tree.update(probe.element(1, 1, mask = DirtyMask.of(phase)))
                settle(tree)
                check(probe.count(Stage.Update) == 1 && probe.count(Stage.Validate) == 1)
                check(probe.count(Stage.Measure) == if (phase == DirtyPhase.Measure) 1 else 0)
                check(probe.count(Stage.Paint) == if (phase == DirtyPhase.Paint || phase == DirtyPhase.Measure) 1 else 0)
                check(probe.count(Stage.Semantics) == if (phase == DirtyPhase.Semantics || phase == DirtyPhase.Measure) 1 else 0)
                check((monitor.snapshot().counts[UiRenderMetric.StructureInvalidation] ?: 0L) == 0L)
                val expectedWidth = if (phase == DirtyPhase.Measure) 2 else 1
                check(tree.measure(Constraints()).width == expectedWidth)
                val fill = tree.paint().single() as DrawCommand.FillRectangle
                check(fill.bounds.width == expectedWidth)
                check(fill.color.value == (0xFF000000.toInt() or if (phase == DirtyPhase.Paint) 1 else 0))
                check(tree.semantics().single().semantics.value == UiText.literal(if (phase == DirtyPhase.Semantics) "1" else "0"))
                monitor.close()
            }
        }
    }

    private fun sameDescription() {
        val probe = EmptyChildProbe()
        val element = probe.element(1, modifier = probe.modifier(1))
        UiTree().use { tree ->
            tree.update(element)
            probe.checkpoint()
            tree.update(element)
            check(probe.count(Stage.Validate) == 1 && probe.count(Stage.ModifierValidate) == 1)
            check(probe.count(Stage.Update) == 0 && probe.count(Stage.ModifierUpdate) == 0)
            check(probe.events.isEmpty())
        }
    }

    private fun coldCreation() {
        val probe = EmptyChildProbe()
        val tree = UiTree()
        tree.update(probe.element(1, children = listOf(probe.element(2))))
        check(probe.count(Stage.Create) == 2 && probe.count(Stage.Attach) == 2)
        check(probe.events == listOf(EmptyChildProbe.Event(Stage.Attach, 1), EmptyChildProbe.Event(Stage.Attach, 2)))
        tree.close()
        check(probe.count(Stage.Detach) == 2 && probe.count(Stage.Dispose) == 2)
        probe.nodes.forEach { node -> check(runCatching(node::invalidateForControl).isFailure) }
    }

    private fun transition(payload: Payload) {
        val workload =
            when (payload) {
                Payload.EmptyToOne -> EmptyChildWorkload.DirectEmptyToOne1
                Payload.OneToEmpty -> EmptyChildWorkload.DirectOneToEmpty1
                Payload.NonemptyToNonempty -> EmptyChildWorkload.DirectNonempty1
                else -> error("Expected a nonempty transition")
            }
        EmptyChildFixture(workload, true).use { fixture ->
            val before = fixture.probe.nodes.toList()
            val monitor = checkNotNull(fixture.monitor)
            val target = monitor.findNodes(ElementKey(0)).single()
            fixture.execute()
            fixture.settle()
            check(monitor.findNodes(ElementKey(0)).single() == target)
            val structural = monitor.snapshot().counts[UiRenderMetric.StructureInvalidation] ?: 0
            check(structural == if (payload == Payload.NonemptyToNonempty) 0L else 1L)
            when (payload) {
                Payload.EmptyToOne -> check(fixture.probe.count(Stage.Create) == 1 && fixture.probe.count(Stage.Attach) == 1)
                Payload.OneToEmpty -> {
                    check(fixture.probe.events == listOf(EmptyChildProbe.Event(Stage.Detach, 1), EmptyChildProbe.Event(Stage.Dispose, 1)))
                    check(before.last().disposed)
                }
                Payload.NonemptyToNonempty -> {
                    check(fixture.probe.count(Stage.Update) == 3 && fixture.probe.events.isEmpty())
                    check(before.last() === fixture.probe.nodes.last())
                }
                else -> error("Expected a transition")
            }
        }
    }

    private fun keyedInsertion() {
        val probe = EmptyChildProbe()
        UiTree().use { tree ->
            tree.update(probe.element(0))
            probe.checkpoint()
            tree.update(probe.element(0, children = listOf(probe.element(2), probe.element(1))))
            settle(tree)
            check(probe.events == listOf(EmptyChildProbe.Event(Stage.Attach, 2), EmptyChildProbe.Event(Stage.Attach, 1)))
            check(tree.semantics().map { it.semantics.label } == listOf(0, 2, 1).map { devLabel(it) })
        }
    }

    private fun keyedRemoval() {
        val probe = EmptyChildProbe()
        UiTree().use { tree ->
            tree.update(probe.element(0, children = listOf(probe.element(1), probe.element(2))))
            probe.checkpoint()
            tree.update(probe.element(0))
            check(probe.events == listOf(2, 1).flatMap { listOf(EmptyChildProbe.Event(Stage.Detach, it), EmptyChildProbe.Event(Stage.Dispose, it)) })
            check(probe.nodes.drop(1).all { it.disposed })
        }
    }

    private fun reorder(modified: Boolean) {
        val probe = EmptyChildProbe()
        fun leaf(id: Int) = probe.element(id, modifier = if (modified) probe.modifier(id) else Modifier.Empty)
        UiTree().use { tree ->
            tree.update(probe.element(0, children = listOf(leaf(1), leaf(2))))
            settle(tree)
            val monitor = tree.startRenderMonitoring()
            val first = monitor.findNodes(ElementKey(1)).single()
            val second = monitor.findNodes(ElementKey(2)).single()
            val previous = monitor.snapshot().nodes.associateBy { it.id }
            probe.checkpoint()
            tree.update(probe.element(0, children = listOf(leaf(2), leaf(1))))
            settle(tree)
            val after = monitor.snapshot()
            check(monitor.findNodes(ElementKey(1)).single() == first && monitor.findNodes(ElementKey(2)).single() == second)
            check(after.nodes.all { previous[it.id]?.parentId == it.parentId })
            check(probe.count(Stage.Create) == 0 && probe.events.isEmpty())
            check(probe.count(Stage.ModifierUpdate) == if (modified) 2 else 0)
            check(after.counts[UiRenderMetric.StructureInvalidation] == 1L)
            check(tree.semantics().map { it.semantics.label } == listOf(0, 2, 1).map(::devLabel))
            monitor.close()
        }
    }

    private fun replacement(keyed: Boolean) {
        val probe = EmptyChildProbe()
        UiTree().use { tree ->
            tree.update(probe.element(0, children = listOf(probe.element(1, keyed = keyed))))
            val old = probe.nodes.last()
            probe.checkpoint()
            tree.update(probe.element(0, children = listOf(probe.element(1, kind = Kind.Alternate, keyed = keyed))))
            check(probe.nodes.last() !== old && old.disposed)
            check(probe.events == listOf(EmptyChildProbe.Event(Stage.Detach, 1), EmptyChildProbe.Event(Stage.Dispose, 1), EmptyChildProbe.Event(Stage.Attach, 1)))
        }
    }

    private fun validation(duplicate: Boolean) {
        val probe = EmptyChildProbe()
        val cause = IllegalArgumentException("local validation")
        UiTree().use { tree ->
            tree.update(probe.element(0))
            val old = probe.nodes.single()
            probe.checkpoint()
            val children = if (duplicate) listOf(probe.element(1), probe.element(1)) else emptyList()
            if (duplicate.not()) probe.failure = EmptyChildProbe.Failure(Stage.Validate, 0, cause)
            val result = runCatching { tree.update(probe.element(0, children = children)) }.exceptionOrNull()
            check(if (duplicate) result is IllegalArgumentException else result === cause)
            check(tree.state === TreeState.Active && probe.count(Stage.Update) == 0)
            check(probe.nodes.single() === old && probe.events.isEmpty())
            probe.failure = null
            tree.update(probe.element(0, 1))
            check(probe.count(Stage.Update) == 1)
        }
    }

    private fun failure(stage: Stage) {
        val probe = EmptyChildProbe()
        val cause = IllegalStateException("primary $stage")
        val tree = UiTree()
        val adding = stage == Stage.Create || stage == Stage.Attach
        val removing = stage == Stage.Detach || stage == Stage.Dispose
        val initialChildren = if (removing) listOf(probe.element(1), probe.element(2)) else emptyList()
        tree.update(probe.element(0, children = initialChildren, modifier = probe.modifier(0)))
        probe.checkpoint()
        val target = if (stage == Stage.Create) 2 else if (adding || removing) 1 else 0
        val detach = IllegalStateException("later root detach")
        val dispose = IllegalStateException("later root dispose")
        probe.cleanupFailures = listOf(
            EmptyChildProbe.Failure(Stage.Detach, 0, detach),
            EmptyChildProbe.Failure(Stage.Dispose, 0, dispose),
        )
        probe.failure = EmptyChildProbe.Failure(stage, target, cause)
        val nextChildren = if (adding) listOf(probe.element(1), probe.element(2)) else emptyList()
        val result = runCatching { tree.update(probe.element(0, 1, nextChildren, probe.modifier(0))) }.exceptionOrNull()
        check(result === cause && tree.state === TreeState.Poisoned)
        check(cause.suppressedExceptions == listOf(detach, dispose))
        check(probe.nodes.all { it.disposed })
        probe.nodes.forEach { check(runCatching(it::invalidateForControl).isFailure) }
        val events = probe.events.toList()
        tree.close()
        check(tree.state === TreeState.Closed && probe.events == events)
        check(events.filter { it.stage == Stage.Dispose }.map { it.id }.distinct().size == events.count { it.stage == Stage.Dispose })
        if (removing) check(events.filter { it.stage == Stage.Detach }.map { it.id } == listOf(2, 1, 0))
    }

    private fun modifierIdentity() {
        val probe = EmptyChildProbe()
        UiTree().use { tree ->
            tree.update(probe.element(1))
            val node = probe.nodes.single()
            val monitor = tree.startRenderMonitoring()
            val componentId = monitor.findNodes(ElementKey(1)).single()
            val hoisted = probe.modifier(1)
            tree.update(probe.element(1, modifier = hoisted))
            val modifierId = monitor.snapshot().nodes.single { it.kind == UiRenderNodeKind.Modifier }.id
            probe.checkpoint()
            tree.update(probe.element(1, modifier = hoisted))
            check(probe.count(Stage.Update) == 1 && probe.count(Stage.ModifierUpdate) == 0)
            check(monitor.snapshot().nodes.single { it.kind == UiRenderNodeKind.Modifier }.id == modifierId)
            tree.update(probe.element(1, modifier = probe.modifier(1)))
            check(probe.count(Stage.ModifierUpdate) == 1)
            check(monitor.snapshot().nodes.single { it.kind == UiRenderNodeKind.Modifier }.id == modifierId)
            tree.update(probe.element(1, modifier = probe.modifier(1, 1)))
            check(probe.count(Stage.ModifierUpdate) == 2)
            val modified = monitor.snapshot()
            check(modified.nodes.single { it.id == modifierId }.retired.not())
            check(modified.nodes.single { it.id == componentId }.parentId == modifierId)
            tree.update(probe.element(1))
            check(probe.nodes.single() === node && node.disposed.not())
            check(monitor.findNodes(ElementKey(1)).single() == componentId)
            check(monitor.snapshot().nodes.single { it.id == modifierId }.retired)
            monitor.close()
        }
    }

    private fun dynamicEmpty() {
        val probe = EmptyChildProbe()
        UiTree().use { tree ->
            tree.update(probe.element(0, kind = Kind.Dynamic))
            tree.measure(Constraints())
            probe.checkpoint()
            probe.dynamicOutput = EmptyOutput()
            tree.update(probe.element(0, 1, kind = Kind.Dynamic))
            tree.measure(Constraints())
            check(probe.count(Stage.Dynamic) == 1 && probe.count(Stage.Update) == 1)
            check(probe.nodes.single().childCount == 0)
            probe.nodes.single().invalidateForControl()
            tree.measure(Constraints())
            check(probe.count(Stage.Dynamic) == 2 && probe.count(Stage.Validate) == 1)
        }
    }

    private fun dynamicOscillation() {
        val probe = EmptyChildProbe()
        UiTree().use { tree ->
            tree.update(probe.element(0, kind = Kind.Dynamic))
            tree.measure(Constraints())
            repeat(3) { value ->
                probe.dynamicOutput = listOf(probe.element(1, value))
                tree.update(probe.element(0, value * 2 + 1, kind = Kind.Dynamic))
                settle(tree)
                check(probe.nodes.first().childCount == 1)
                probe.dynamicOutput = emptyList()
                tree.update(probe.element(0, value * 2 + 2, kind = Kind.Dynamic))
                settle(tree)
                check(probe.nodes.first().childCount == 0)
            }
            check(probe.count(Stage.Create) == 4 && probe.count(Stage.Dispose) == 3)
            check(probe.nodes.drop(1).all { it.disposed })
        }
    }

    private fun deferredEmpty() {
        val source = Source(Output.Populated)
        val probe = EmptyChildProbe()
        var evaluations = 0
        val session = createRuntimeUiSession {
            evaluateComponentTree {
                Observe(source) { value -> evaluations += 1; if (value == Output.Populated) element(probe.element(1)) }
            }
        }
        val monitor = session.startRenderMonitoring()
        session.use {
            session.attach()
            session.frame(Constraints())
            monitor.checkpoint()
            source.publish(Output.Empty)
            check(evaluations == 1)
            session.frame(Constraints())
            check(evaluations == 2 && probe.nodes.single().disposed)
            source.publish(Output.NextEmpty)
            val empty = session.frame(Constraints())
            check(evaluations == 3 && empty.drawCommands.isEmpty() && empty.semantics.isEmpty())
            check(session.frame(Constraints()) === empty && evaluations == 3)
            check(monitor.snapshot().counts[UiRenderMetric.SourceContent] == 2L)
        }
        check(source.subscribed.not())
        cutoff()
    }

    private fun localized() {
        val source = Source(0)
        val probe = EmptyChildProbe()
        var roots = 0
        var regions = 0
        val session = createRuntimeUiSession {
            roots += 1
            evaluateComponentTree {
                Stack {
                    element(probe.element(99))
                    Observe(source) { value -> regions += 1; element(probe.element(1, value)) }
                }
            }
        }
        session.use {
            session.attach()
            session.frame(Constraints())
            val unaffected = probe.nodes.first { it.id == 99 }
            probe.checkpoint()
            source.publish(1)
            session.frame(Constraints())
            check(roots == 1 && regions == 2 && probe.count(Stage.Update) == 1)
            check(probe.nodes.first { it.id == 99 } === unaffected)
            check(probe.count(Stage.Measure) == 1 && probe.count(Stage.Paint) == 1 && probe.count(Stage.Semantics) == 1)
        }
    }

    private fun frameIdentity() {
        EmptyChildFixture(EmptyChildWorkload.Clean128, true).use { fixture ->
            check(fixture.execute() === fixture.initialFrame)
            check(fixture.observations().all { it == 0 })
            check(checkNotNull(fixture.monitor).snapshot().counts[UiRenderMetric.FrameCacheHit] == 1L)
        }
    }

    private fun monitoringParity() {
        EmptyChildWorkload.entries.forEach { workload ->
            val disabled = EmptyChildFixture(workload, false).use { fixture -> fixture.execute(); fixture.settle(); fixture.observations() }
            val enabled = EmptyChildFixture(workload, true).use { fixture -> fixture.execute(); fixture.settle(); fixture.observations() }
            check(disabled == enabled) { "Monitoring changed independent callbacks: $workload" }
        }
    }

    private fun ownerIsolation() {
        val first = RuntimeExecutionOwner()
        val second = RuntimeExecutionOwner()
        val probe = EmptyChildProbe()
        val description = probe.element(1)
        val a = first.run { UiTree().also { it.update(description) } }
        val b = second.run { UiTree().also { it.update(description) } }
        check(probe.nodes[0] !== probe.nodes[1])
        second.run { check(runCatching { a.update(description) }.isFailure) }
        first.run { a.close() }
        second.run { b.update(probe.element(1, 1)); check(b.state === TreeState.Active); b.close() }
        check(probe.nodes.all { it.disposed })
    }

    private fun terminal() {
        val source = Source(0)
        val probe = EmptyChildProbe()
        val session = createRuntimeUiSession { evaluateComponentTree { Observe(source) { element(probe.element(1, it)) } } }
        session.attach()
        session.frame(Constraints())
        val node = probe.nodes.single()
        session.close()
        session.close()
        check(source.subscribed.not() && node.disposed && probe.count(Stage.Dispose) == 1)
        check(runCatching(node::invalidateForControl).isFailure)
        source.publish(1)
        check(probe.count(Stage.Update) == 0)
        failure(Stage.Create)
        failure(Stage.Update)
    }

    /**
     * Counts actual immutable empty dynamic-list iteration independently of the guard.
     * Qualification compares the two archives' observations; declarations and assertions are identical.
     * Validation still iterates this complete incoming sibling set, while the eligible matching loop can be omitted.
     */
    public fun emptyMatchingIterations(): Int {
        val probe = EmptyChildProbe()
        return UiTree().use { tree ->
            tree.update(probe.element(0, kind = Kind.Dynamic))
            tree.measure(Constraints())
            val output = EmptyOutput()
            probe.dynamicOutput = output
            tree.update(probe.element(0, 1, kind = Kind.Dynamic))
            tree.measure(Constraints())
            check(probe.count(Stage.Update) == 1 && probe.nodes.single().childCount == 0)
            val iterations = output.iterations
            probe.nodes.single().invalidateForControl()
            tree.measure(Constraints())
            check(output.iterations == iterations)
            iterations
        }
    }

    private fun cutoff() {
        val source = Source(0)
        val probe = EmptyChildProbe()
        var evaluations = 0
        createRuntimeUiSession {
            evaluateComponentTree { Observe(source) { evaluations += 1; element(probe.element(1, it)) } }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            probe.onUpdate = { probe.onUpdate = null; source.publish(2) }
            source.publish(1)
            session.frame(Constraints())
            check(probe.nodes.single().payload == 1 && evaluations == 2)
            session.frame(Constraints())
            check(probe.nodes.single().payload == 2 && evaluations == 3)
        }
        check(source.subscribed.not())
    }

    /**
     * Typed dynamic-output states; the external source boundary carries these values directly.
     */
    private enum class Output { Populated, Empty, NextEmpty }

    /**
     * New immutable empty-list identity whose iteration is independently observable outside timing.
     */
    private class EmptyOutput : AbstractList<Element>() {
        /**
         * Actual consumers of the complete incoming list.
         */
        var iterations = 0
        override val size: Int get() = 0
        override fun get(index: Int): Element = throw IndexOutOfBoundsException(index.toString())
        override fun iterator(): Iterator<Element> {
            iterations += 1
            return emptyList<Element>().iterator()
        }
    }

    private fun settle(tree: UiTree) {
        tree.measure(Constraints())
        tree.layout()
        tree.paint()
        tree.semantics()
    }

    private fun devLabel(id: Int) = UiText.literal(id.toString())

    /**
     * Owner-confined source retaining exactly one current snapshot and one callback.
     */
    private class Source<T>(initial: T) : StateSource<T> {
        private var snapshot = StateSnapshot(StateRevision(0), initial)
        private var observer: ((StateSnapshot<T>) -> Unit)? = null
        /**
         * Current subscription lifetime for explicit terminal assertions.
         */
        val subscribed: Boolean get() = observer != null
        override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
            check(this.observer == null)
            this.observer = observer
            return StateSubscription(snapshot) { this.observer = null }
        }
        /**
         * Enqueues a new source revision; no content callback runs before the public frame boundary.
         */
        fun publish(value: T) {
            snapshot = StateSnapshot(StateRevision(snapshot.revision.value + 1), value)
            observer?.invoke(snapshot)
        }
    }
}
