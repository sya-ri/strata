package dev.s7a.strata.quality.benchmark

/**
 * Finite untimed acceptance registry, independent of the 116 timed operation cases.
 * Each scenario uses the real service, public definitions, authenticated packets and actual callbacks.
 */
internal enum class RemoteServiceGate {
    ReadyMutation,
    TickContentOrReportMutation,
    OpenedMutation,
    PresentationChangedMutation,
    ClosedMutation,
    SamePlayerReplacement,
    TerminalMutation,
    NestedTick,
    NestedOpenReceiveRefresh,
    CallbackReportSendFailure,
    SessionCloseFailure,
    AdmissionBudgets,
    TransitionBudget,
    DuplicateStaleInput,
    RemoveReadmitShrinkChurn,
    OwnerMigrationIsolationRelease,
    ;

    /**
     * Typed legal callback attempt or failure site; numeric depth remains an input, not a discriminator.
     */
    enum class Trigger {
        None,
        Close,
        Open,
        Replace,
        ReportClose,
        ReportReplace,
        Disconnect,
        OwnerDisabled,
        ServiceClose,
        OpenRefresh,
        ReceiveRefresh,
        NotifyFailure,
        ReportFailure,
        SendFailure,
        HudBudget,
        NodeBudget,
        ReleaseFailure,
    }

    /**
     * Complete finite gate identity; membership/depth inputs are fixed before executing either runtime.
     */
    data class Case(
        val gate: RemoteServiceGate,
        val topology: RemoteServiceShape,
        val trigger: Trigger = Trigger.None,
        val targetSessions: Int = topology.sessionsPerPeer,
        val depth: Int = 1,
    ) {
        /**
         * Adapts a timed shape where the gate's required input naturally matches it.
         */
        constructor(gate: RemoteServiceGate, topology: RemoteServiceTopology, trigger: Trigger = Trigger.None, targetSessions: Int = topology.sessionsPerPeer, depth: Int = 1) :
            this(gate, RemoteServiceShape(topology.peers, topology.huds, topology.screens), trigger, targetSessions, depth)
    }

    /**
     * Frozen required variants, with no discovery based on comparison outcomes.
     */
    companion object {
        /**
         * Lists the complete independent registry in stable scenario/variant order.
         */
        fun cases(): List<Case> =
            buildList {
                listOf(1, 4, 17).forEach { add(Case(ReadyMutation, RemoteServiceTopology.Peers1Huds0Screens0, targetSessions = it)) }
                listOf(Trigger.Close, Trigger.Open, Trigger.Replace, Trigger.ReportClose, Trigger.ReportReplace).forEach { add(Case(TickContentOrReportMutation, RemoteServiceTopology.Peers1Huds4Screens0, it)) }
                listOf(RemoteServiceTopology.Peers1Huds1Screens0, RemoteServiceTopology.Peers1Huds4Screens0).forEach { add(Case(OpenedMutation, it)) }
                add(Case(PresentationChangedMutation, RemoteServiceTopology.Peers1Huds4Screens1))
                add(Case(ClosedMutation, RemoteServiceTopology.Peers1Huds1Screens0))
                listOf(RemoteServiceShape(2, 1, 0), RemoteServiceShape(2, 16, 1)).forEach { add(Case(SamePlayerReplacement, it)) }
                listOf(RemoteServiceShape(2, 4, 0), RemoteServiceShape(2, 16, 1)).forEach { topology ->
                    listOf(Trigger.Disconnect, Trigger.OwnerDisabled, Trigger.ServiceClose).forEach { add(Case(TerminalMutation, topology, it)) }
                }
                listOf(RemoteServiceShape(1, 1, 0), RemoteServiceShape(2, 4, 0)).forEach { topology ->
                    listOf(2, 4).forEach { add(Case(NestedTick, topology, depth = it)) }
                }
                listOf(Trigger.OpenRefresh, Trigger.ReceiveRefresh).forEach { add(Case(NestedOpenReceiveRefresh, RemoteServiceTopology.Peers1Huds4Screens0, it)) }
                listOf(Trigger.NotifyFailure, Trigger.ReportFailure, Trigger.SendFailure).forEach { add(Case(CallbackReportSendFailure, RemoteServiceShape(2, 4, 0), it)) }
                add(Case(SessionCloseFailure, RemoteServiceShape(2, 16, 1)))
                add(Case(AdmissionBudgets, RemoteServiceTopology.Peers1Huds16Screens1, Trigger.HudBudget))
                add(Case(AdmissionBudgets, RemoteServiceShape(2, 16, 1), Trigger.NodeBudget))
                add(Case(TransitionBudget, RemoteServiceTopology.Empty))
                listOf(RemoteServiceShape(2, 1, 0), RemoteServiceShape(2, 4, 0)).forEach { add(Case(DuplicateStaleInput, it)) }
                add(Case(RemoveReadmitShrinkChurn, RemoteServiceTopology.Peers1Huds16Screens1))
                listOf(RemoteServiceShape(2, 0, 0), RemoteServiceShape(2, 1, 0), RemoteServiceShape(2, 4, 0), RemoteServiceShape(2, 16, 1)).forEach { topology ->
                    listOf(Trigger.None, Trigger.ReleaseFailure).forEach { add(Case(OwnerMigrationIsolationRelease, topology, it)) }
                }
            }
    }
}
