package dev.s7a.strata.quality.benchmark

/** Exact 70 complete-operation cells from Issue 252; the single enum prevents invalid parameter products. */
public enum class FlowMeasurementCase(
    public val topology: Topology,
    public val operation: Operation,
) {
    F001(Topology.Empty, Operation.ResizeFrame),
    F002(Topology.Empty, Operation.SourceFrame),
    F003(Topology.Empty, Operation.Lifecycle),
    F004(Topology.Empty, Operation.IdleFrame),
    F005(Topology.Empty, Operation.LayoutOnlyFrame),
    F006(Topology.Single, Operation.ResizeFrame),
    F007(Topology.Single, Operation.SourceFrame),
    F008(Topology.Single, Operation.Lifecycle),
    F009(Topology.Single, Operation.IdleFrame),
    F010(Topology.Single, Operation.LayoutOnlyFrame),
    F011(Topology.OnePerRow16, Operation.ResizeFrame),
    F012(Topology.OnePerRow16, Operation.SourceFrame),
    F013(Topology.OnePerRow16, Operation.Lifecycle),
    F014(Topology.OnePerRow16, Operation.IdleFrame),
    F015(Topology.OnePerRow16, Operation.LayoutOnlyFrame),
    F016(Topology.PackedFour16, Operation.ResizeFrame),
    F017(Topology.PackedFour16, Operation.SourceFrame),
    F018(Topology.PackedFour16, Operation.Lifecycle),
    F019(Topology.PackedFour16, Operation.IdleFrame),
    F020(Topology.PackedFour16, Operation.LayoutOnlyFrame),
    F021(Topology.ExactBoundary16, Operation.ResizeFrame),
    F022(Topology.ExactBoundary16, Operation.SourceFrame),
    F023(Topology.ExactBoundary16, Operation.Lifecycle),
    F024(Topology.ExactBoundary16, Operation.IdleFrame),
    F025(Topology.ExactBoundary16, Operation.LayoutOnlyFrame),
    F026(Topology.Unbounded16, Operation.ResizeFrame),
    F027(Topology.Unbounded16, Operation.SourceFrame),
    F028(Topology.Unbounded16, Operation.Lifecycle),
    F029(Topology.Unbounded16, Operation.IdleFrame),
    F030(Topology.Unbounded16, Operation.LayoutOnlyFrame),
    F031(Topology.OnePerRow128, Operation.ResizeFrame),
    F032(Topology.OnePerRow128, Operation.SourceFrame),
    F033(Topology.OnePerRow128, Operation.Lifecycle),
    F034(Topology.OnePerRow128, Operation.IdleFrame),
    F035(Topology.OnePerRow128, Operation.LayoutOnlyFrame),
    F036(Topology.PackedFour128, Operation.ResizeFrame),
    F037(Topology.PackedFour128, Operation.SourceFrame),
    F038(Topology.PackedFour128, Operation.Lifecycle),
    F039(Topology.PackedFour128, Operation.IdleFrame),
    F040(Topology.PackedFour128, Operation.LayoutOnlyFrame),
    F041(Topology.ExactBoundary128, Operation.ResizeFrame),
    F042(Topology.ExactBoundary128, Operation.SourceFrame),
    F043(Topology.ExactBoundary128, Operation.Lifecycle),
    F044(Topology.ExactBoundary128, Operation.IdleFrame),
    F045(Topology.ExactBoundary128, Operation.LayoutOnlyFrame),
    F046(Topology.Unbounded128, Operation.ResizeFrame),
    F047(Topology.Unbounded128, Operation.SourceFrame),
    F048(Topology.Unbounded128, Operation.Lifecycle),
    F049(Topology.Unbounded128, Operation.IdleFrame),
    F050(Topology.Unbounded128, Operation.LayoutOnlyFrame),
    F051(Topology.OnePerRow4096, Operation.ResizeFrame),
    F052(Topology.OnePerRow4096, Operation.SourceFrame),
    F053(Topology.OnePerRow4096, Operation.Lifecycle),
    F054(Topology.OnePerRow4096, Operation.IdleFrame),
    F055(Topology.OnePerRow4096, Operation.LayoutOnlyFrame),
    F056(Topology.PackedFour4096, Operation.ResizeFrame),
    F057(Topology.PackedFour4096, Operation.SourceFrame),
    F058(Topology.PackedFour4096, Operation.Lifecycle),
    F059(Topology.PackedFour4096, Operation.IdleFrame),
    F060(Topology.PackedFour4096, Operation.LayoutOnlyFrame),
    F061(Topology.ExactBoundary4096, Operation.ResizeFrame),
    F062(Topology.ExactBoundary4096, Operation.SourceFrame),
    F063(Topology.ExactBoundary4096, Operation.Lifecycle),
    F064(Topology.ExactBoundary4096, Operation.IdleFrame),
    F065(Topology.ExactBoundary4096, Operation.LayoutOnlyFrame),
    F066(Topology.Unbounded4096, Operation.ResizeFrame),
    F067(Topology.Unbounded4096, Operation.SourceFrame),
    F068(Topology.Unbounded4096, Operation.Lifecycle),
    F069(Topology.Unbounded4096, Operation.IdleFrame),
    F070(Topology.Unbounded4096, Operation.LayoutOnlyFrame),
    ;

    /** One complete retained frame or independent terminal lifetime. */
    public enum class Operation { ResizeFrame, SourceFrame, Lifecycle, IdleFrame, LayoutOnlyFrame }

    /** Greedy-row shapes fixed independently of candidate partition helpers. */
    public enum class Shape { Empty, Single, OnePerRow, PackedFour, ExactBoundary, Unbounded }

    /** Immutable admitted input rows; all gaps are one and finite cross maximum is 180. */
    public enum class Topology(
        public val childCount: Int,
        public val shape: Shape,
        public val maximumWidth: Int,
    ) {
        Empty(0, Shape.Empty, 32),
        Single(1, Shape.Single, 7),
        OnePerRow16(16, Shape.OnePerRow, 1),
        PackedFour16(16, Shape.PackedFour, 7),
        ExactBoundary16(16, Shape.ExactBoundary, 7),
        Unbounded16(16, Shape.Unbounded, Int.MAX_VALUE),
        OnePerRow128(128, Shape.OnePerRow, 1),
        PackedFour128(128, Shape.PackedFour, 7),
        ExactBoundary128(128, Shape.ExactBoundary, 7),
        Unbounded128(128, Shape.Unbounded, Int.MAX_VALUE),
        OnePerRow4096(4_096, Shape.OnePerRow, 1),
        PackedFour4096(4_096, Shape.PackedFour, 7),
        ExactBoundary4096(4_096, Shape.ExactBoundary, 7),
        Unbounded4096(4_096, Shape.Unbounded, Int.MAX_VALUE),
        ;

        /** Source-independent positive child width, including exact-boundary cyclic inputs. */
        public fun width(index: Int): Int = if (shape == Shape.ExactBoundary && index % 2 == 0) 2 else 1

        /** Immutable alternating main constraint for resize; the unbounded sentinel remains unchanged. */
        public val resizedMaximumWidth: Int
            get() =
                when (shape) {
                    Shape.Empty -> 33
                    Shape.OnePerRow -> 2
                    Shape.Unbounded -> Int.MAX_VALUE
                    Shape.Single, Shape.PackedFour, Shape.ExactBoundary -> 9
                }
    }
}
