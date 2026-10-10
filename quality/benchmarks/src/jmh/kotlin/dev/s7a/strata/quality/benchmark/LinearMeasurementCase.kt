package dev.s7a.strata.quality.benchmark

/** Complete finite 110-case Issue #251 matrix; combinations outside this inventory are not admitted. */
public enum class LinearMeasurementCase(
    public val topology: Topology,
    public val operation: Operation,
) {
    /** Row, 0 children, None, Finite, ResizeFrame. */
    L001(Topology.RowNone0, Operation.ResizeFrame),

    /** Row, 0 children, None, Finite, SourceFrame. */
    L002(Topology.RowNone0, Operation.SourceFrame),

    /** Row, 0 children, None, Finite, Lifecycle. */
    L003(Topology.RowNone0, Operation.Lifecycle),

    /** Row, 1 children, None, Finite, ResizeFrame. */
    L004(Topology.RowNone1, Operation.ResizeFrame),

    /** Row, 1 children, None, Finite, SourceFrame. */
    L005(Topology.RowNone1, Operation.SourceFrame),

    /** Row, 1 children, None, Finite, Lifecycle. */
    L006(Topology.RowNone1, Operation.Lifecycle),

    /** Row, 16 children, None, Finite, ResizeFrame. */
    L007(Topology.RowNone16, Operation.ResizeFrame),

    /** Row, 16 children, None, Finite, SourceFrame. */
    L008(Topology.RowNone16, Operation.SourceFrame),

    /** Row, 16 children, None, Finite, Lifecycle. */
    L009(Topology.RowNone16, Operation.Lifecycle),

    /** Row, 128 children, None, Finite, ResizeFrame. */
    L010(Topology.RowNone128, Operation.ResizeFrame),

    /** Row, 128 children, None, Finite, SourceFrame. */
    L011(Topology.RowNone128, Operation.SourceFrame),

    /** Row, 128 children, None, Finite, Lifecycle. */
    L012(Topology.RowNone128, Operation.Lifecycle),

    /** Row, 4096 children, None, Finite, ResizeFrame. */
    L013(Topology.RowNone4096, Operation.ResizeFrame),

    /** Row, 4096 children, None, Finite, SourceFrame. */
    L014(Topology.RowNone4096, Operation.SourceFrame),

    /** Row, 4096 children, None, Finite, Lifecycle. */
    L015(Topology.RowNone4096, Operation.Lifecycle),

    /** Row, 128 children, SparseEndsFill, Finite, ResizeFrame. */
    L016(Topology.RowSparseEndsFill128Finite, Operation.ResizeFrame),

    /** Row, 128 children, SparseEndsFill, Finite, SourceFrame. */
    L017(Topology.RowSparseEndsFill128Finite, Operation.SourceFrame),

    /** Row, 128 children, SparseEndsFill, Finite, Lifecycle. */
    L018(Topology.RowSparseEndsFill128Finite, Operation.Lifecycle),

    /** Row, 128 children, SparseEndsFill, IntrinsicMain, ResizeFrame. */
    L019(Topology.RowSparseEndsFill128IntrinsicMain, Operation.ResizeFrame),

    /** Row, 128 children, SparseEndsFill, IntrinsicMain, SourceFrame. */
    L020(Topology.RowSparseEndsFill128IntrinsicMain, Operation.SourceFrame),

    /** Row, 128 children, SparseEndsFill, IntrinsicMain, Lifecycle. */
    L021(Topology.RowSparseEndsFill128IntrinsicMain, Operation.Lifecycle),

    /** Row, 128 children, AlternatingFill, Finite, ResizeFrame. */
    L022(Topology.RowAlternatingFill128Finite, Operation.ResizeFrame),

    /** Row, 128 children, AlternatingFill, Finite, SourceFrame. */
    L023(Topology.RowAlternatingFill128Finite, Operation.SourceFrame),

    /** Row, 128 children, AlternatingFill, Finite, Lifecycle. */
    L024(Topology.RowAlternatingFill128Finite, Operation.Lifecycle),

    /** Row, 128 children, AlternatingFill, IntrinsicMain, ResizeFrame. */
    L025(Topology.RowAlternatingFill128IntrinsicMain, Operation.ResizeFrame),

    /** Row, 128 children, AlternatingFill, IntrinsicMain, SourceFrame. */
    L026(Topology.RowAlternatingFill128IntrinsicMain, Operation.SourceFrame),

    /** Row, 128 children, AlternatingFill, IntrinsicMain, Lifecycle. */
    L027(Topology.RowAlternatingFill128IntrinsicMain, Operation.Lifecycle),

    /** Row, 128 children, AllFill, Finite, ResizeFrame. */
    L028(Topology.RowAllFill128Finite, Operation.ResizeFrame),

    /** Row, 128 children, AllFill, Finite, SourceFrame. */
    L029(Topology.RowAllFill128Finite, Operation.SourceFrame),

    /** Row, 128 children, AllFill, Finite, Lifecycle. */
    L030(Topology.RowAllFill128Finite, Operation.Lifecycle),

    /** Row, 128 children, AllFill, IntrinsicMain, ResizeFrame. */
    L031(Topology.RowAllFill128IntrinsicMain, Operation.ResizeFrame),

    /** Row, 128 children, AllFill, IntrinsicMain, SourceFrame. */
    L032(Topology.RowAllFill128IntrinsicMain, Operation.SourceFrame),

    /** Row, 128 children, AllFill, IntrinsicMain, Lifecycle. */
    L033(Topology.RowAllFill128IntrinsicMain, Operation.Lifecycle),

    /** Row, 128 children, AllNonfill, Finite, ResizeFrame. */
    L034(Topology.RowAllNonfill128Finite, Operation.ResizeFrame),

    /** Row, 128 children, AllNonfill, Finite, SourceFrame. */
    L035(Topology.RowAllNonfill128Finite, Operation.SourceFrame),

    /** Row, 128 children, AllNonfill, Finite, Lifecycle. */
    L036(Topology.RowAllNonfill128Finite, Operation.Lifecycle),

    /** Row, 128 children, AllNonfill, IntrinsicMain, ResizeFrame. */
    L037(Topology.RowAllNonfill128IntrinsicMain, Operation.ResizeFrame),

    /** Row, 128 children, AllNonfill, IntrinsicMain, SourceFrame. */
    L038(Topology.RowAllNonfill128IntrinsicMain, Operation.SourceFrame),

    /** Row, 128 children, AllNonfill, IntrinsicMain, Lifecycle. */
    L039(Topology.RowAllNonfill128IntrinsicMain, Operation.Lifecycle),

    /** Row, 4096 children, AllFill, Finite, ResizeFrame. */
    L040(Topology.RowAllFill4096Finite, Operation.ResizeFrame),

    /** Row, 4096 children, AllFill, Finite, SourceFrame. */
    L041(Topology.RowAllFill4096Finite, Operation.SourceFrame),

    /** Row, 4096 children, AllFill, Finite, Lifecycle. */
    L042(Topology.RowAllFill4096Finite, Operation.Lifecycle),

    /** Row, 4096 children, AllFill, IntrinsicMain, ResizeFrame. */
    L043(Topology.RowAllFill4096IntrinsicMain, Operation.ResizeFrame),

    /** Row, 4096 children, AllFill, IntrinsicMain, SourceFrame. */
    L044(Topology.RowAllFill4096IntrinsicMain, Operation.SourceFrame),

    /** Row, 4096 children, AllFill, IntrinsicMain, Lifecycle. */
    L045(Topology.RowAllFill4096IntrinsicMain, Operation.Lifecycle),

    /** Column, 0 children, None, Finite, ResizeFrame. */
    L046(Topology.ColumnNone0, Operation.ResizeFrame),

    /** Column, 0 children, None, Finite, SourceFrame. */
    L047(Topology.ColumnNone0, Operation.SourceFrame),

    /** Column, 0 children, None, Finite, Lifecycle. */
    L048(Topology.ColumnNone0, Operation.Lifecycle),

    /** Column, 1 children, None, Finite, ResizeFrame. */
    L049(Topology.ColumnNone1, Operation.ResizeFrame),

    /** Column, 1 children, None, Finite, SourceFrame. */
    L050(Topology.ColumnNone1, Operation.SourceFrame),

    /** Column, 1 children, None, Finite, Lifecycle. */
    L051(Topology.ColumnNone1, Operation.Lifecycle),

    /** Column, 16 children, None, Finite, ResizeFrame. */
    L052(Topology.ColumnNone16, Operation.ResizeFrame),

    /** Column, 16 children, None, Finite, SourceFrame. */
    L053(Topology.ColumnNone16, Operation.SourceFrame),

    /** Column, 16 children, None, Finite, Lifecycle. */
    L054(Topology.ColumnNone16, Operation.Lifecycle),

    /** Column, 128 children, None, Finite, ResizeFrame. */
    L055(Topology.ColumnNone128, Operation.ResizeFrame),

    /** Column, 128 children, None, Finite, SourceFrame. */
    L056(Topology.ColumnNone128, Operation.SourceFrame),

    /** Column, 128 children, None, Finite, Lifecycle. */
    L057(Topology.ColumnNone128, Operation.Lifecycle),

    /** Column, 4096 children, None, Finite, ResizeFrame. */
    L058(Topology.ColumnNone4096, Operation.ResizeFrame),

    /** Column, 4096 children, None, Finite, SourceFrame. */
    L059(Topology.ColumnNone4096, Operation.SourceFrame),

    /** Column, 4096 children, None, Finite, Lifecycle. */
    L060(Topology.ColumnNone4096, Operation.Lifecycle),

    /** Column, 128 children, SparseEndsFill, Finite, ResizeFrame. */
    L061(Topology.ColumnSparseEndsFill128Finite, Operation.ResizeFrame),

    /** Column, 128 children, SparseEndsFill, Finite, SourceFrame. */
    L062(Topology.ColumnSparseEndsFill128Finite, Operation.SourceFrame),

    /** Column, 128 children, SparseEndsFill, Finite, Lifecycle. */
    L063(Topology.ColumnSparseEndsFill128Finite, Operation.Lifecycle),

    /** Column, 128 children, SparseEndsFill, IntrinsicMain, ResizeFrame. */
    L064(Topology.ColumnSparseEndsFill128IntrinsicMain, Operation.ResizeFrame),

    /** Column, 128 children, SparseEndsFill, IntrinsicMain, SourceFrame. */
    L065(Topology.ColumnSparseEndsFill128IntrinsicMain, Operation.SourceFrame),

    /** Column, 128 children, SparseEndsFill, IntrinsicMain, Lifecycle. */
    L066(Topology.ColumnSparseEndsFill128IntrinsicMain, Operation.Lifecycle),

    /** Column, 128 children, AlternatingFill, Finite, ResizeFrame. */
    L067(Topology.ColumnAlternatingFill128Finite, Operation.ResizeFrame),

    /** Column, 128 children, AlternatingFill, Finite, SourceFrame. */
    L068(Topology.ColumnAlternatingFill128Finite, Operation.SourceFrame),

    /** Column, 128 children, AlternatingFill, Finite, Lifecycle. */
    L069(Topology.ColumnAlternatingFill128Finite, Operation.Lifecycle),

    /** Column, 128 children, AlternatingFill, IntrinsicMain, ResizeFrame. */
    L070(Topology.ColumnAlternatingFill128IntrinsicMain, Operation.ResizeFrame),

    /** Column, 128 children, AlternatingFill, IntrinsicMain, SourceFrame. */
    L071(Topology.ColumnAlternatingFill128IntrinsicMain, Operation.SourceFrame),

    /** Column, 128 children, AlternatingFill, IntrinsicMain, Lifecycle. */
    L072(Topology.ColumnAlternatingFill128IntrinsicMain, Operation.Lifecycle),

    /** Column, 128 children, AllFill, Finite, ResizeFrame. */
    L073(Topology.ColumnAllFill128Finite, Operation.ResizeFrame),

    /** Column, 128 children, AllFill, Finite, SourceFrame. */
    L074(Topology.ColumnAllFill128Finite, Operation.SourceFrame),

    /** Column, 128 children, AllFill, Finite, Lifecycle. */
    L075(Topology.ColumnAllFill128Finite, Operation.Lifecycle),

    /** Column, 128 children, AllFill, IntrinsicMain, ResizeFrame. */
    L076(Topology.ColumnAllFill128IntrinsicMain, Operation.ResizeFrame),

    /** Column, 128 children, AllFill, IntrinsicMain, SourceFrame. */
    L077(Topology.ColumnAllFill128IntrinsicMain, Operation.SourceFrame),

    /** Column, 128 children, AllFill, IntrinsicMain, Lifecycle. */
    L078(Topology.ColumnAllFill128IntrinsicMain, Operation.Lifecycle),

    /** Column, 128 children, AllNonfill, Finite, ResizeFrame. */
    L079(Topology.ColumnAllNonfill128Finite, Operation.ResizeFrame),

    /** Column, 128 children, AllNonfill, Finite, SourceFrame. */
    L080(Topology.ColumnAllNonfill128Finite, Operation.SourceFrame),

    /** Column, 128 children, AllNonfill, Finite, Lifecycle. */
    L081(Topology.ColumnAllNonfill128Finite, Operation.Lifecycle),

    /** Column, 128 children, AllNonfill, IntrinsicMain, ResizeFrame. */
    L082(Topology.ColumnAllNonfill128IntrinsicMain, Operation.ResizeFrame),

    /** Column, 128 children, AllNonfill, IntrinsicMain, SourceFrame. */
    L083(Topology.ColumnAllNonfill128IntrinsicMain, Operation.SourceFrame),

    /** Column, 128 children, AllNonfill, IntrinsicMain, Lifecycle. */
    L084(Topology.ColumnAllNonfill128IntrinsicMain, Operation.Lifecycle),

    /** Column, 4096 children, AllFill, Finite, ResizeFrame. */
    L085(Topology.ColumnAllFill4096Finite, Operation.ResizeFrame),

    /** Column, 4096 children, AllFill, Finite, SourceFrame. */
    L086(Topology.ColumnAllFill4096Finite, Operation.SourceFrame),

    /** Column, 4096 children, AllFill, Finite, Lifecycle. */
    L087(Topology.ColumnAllFill4096Finite, Operation.Lifecycle),

    /** Column, 4096 children, AllFill, IntrinsicMain, ResizeFrame. */
    L088(Topology.ColumnAllFill4096IntrinsicMain, Operation.ResizeFrame),

    /** Column, 4096 children, AllFill, IntrinsicMain, SourceFrame. */
    L089(Topology.ColumnAllFill4096IntrinsicMain, Operation.SourceFrame),

    /** Column, 4096 children, AllFill, IntrinsicMain, Lifecycle. */
    L090(Topology.ColumnAllFill4096IntrinsicMain, Operation.Lifecycle),

    /** Row, 0 children, None, Finite, IdleFrame. */
    L091(Topology.RowNone0, Operation.IdleFrame),

    /** Row, 0 children, None, Finite, LayoutOnlyFrame. */
    L092(Topology.RowNone0, Operation.LayoutOnlyFrame),

    /** Row, 1 children, None, Finite, IdleFrame. */
    L093(Topology.RowNone1, Operation.IdleFrame),

    /** Row, 1 children, None, Finite, LayoutOnlyFrame. */
    L094(Topology.RowNone1, Operation.LayoutOnlyFrame),

    /** Row, 16 children, None, Finite, IdleFrame. */
    L095(Topology.RowNone16, Operation.IdleFrame),

    /** Row, 16 children, None, Finite, LayoutOnlyFrame. */
    L096(Topology.RowNone16, Operation.LayoutOnlyFrame),

    /** Row, 128 children, None, Finite, IdleFrame. */
    L097(Topology.RowNone128, Operation.IdleFrame),

    /** Row, 128 children, None, Finite, LayoutOnlyFrame. */
    L098(Topology.RowNone128, Operation.LayoutOnlyFrame),

    /** Row, 4096 children, None, Finite, IdleFrame. */
    L099(Topology.RowNone4096, Operation.IdleFrame),

    /** Row, 4096 children, None, Finite, LayoutOnlyFrame. */
    L100(Topology.RowNone4096, Operation.LayoutOnlyFrame),

    /** Column, 0 children, None, Finite, IdleFrame. */
    L101(Topology.ColumnNone0, Operation.IdleFrame),

    /** Column, 0 children, None, Finite, LayoutOnlyFrame. */
    L102(Topology.ColumnNone0, Operation.LayoutOnlyFrame),

    /** Column, 1 children, None, Finite, IdleFrame. */
    L103(Topology.ColumnNone1, Operation.IdleFrame),

    /** Column, 1 children, None, Finite, LayoutOnlyFrame. */
    L104(Topology.ColumnNone1, Operation.LayoutOnlyFrame),

    /** Column, 16 children, None, Finite, IdleFrame. */
    L105(Topology.ColumnNone16, Operation.IdleFrame),

    /** Column, 16 children, None, Finite, LayoutOnlyFrame. */
    L106(Topology.ColumnNone16, Operation.LayoutOnlyFrame),

    /** Column, 128 children, None, Finite, IdleFrame. */
    L107(Topology.ColumnNone128, Operation.IdleFrame),

    /** Column, 128 children, None, Finite, LayoutOnlyFrame. */
    L108(Topology.ColumnNone128, Operation.LayoutOnlyFrame),

    /** Column, 4096 children, None, Finite, IdleFrame. */
    L109(Topology.ColumnNone4096, Operation.IdleFrame),

    /** Column, 4096 children, None, Finite, LayoutOnlyFrame. */
    L110(Topology.ColumnNone4096, Operation.LayoutOnlyFrame),
    ;

    /** Standard layout axis; only the main-axis extent alternates. */
    public enum class Axis { Row, Column }

    /** Immutable parent-data distributions declared in the issue. */
    public enum class Shape { None, SparseEndsFill, AlternatingFill, AllFill, AllNonfill }

    /** Finite fixed bounds or zero-minimum unbounded main-axis bounds. */
    public enum class Bounds { Finite, IntrinsicMain }

    /** Whole retained operation boundaries, with construction and close included in Lifecycle. */
    public enum class Operation { ResizeFrame, SourceFrame, Lifecycle, IdleFrame, LayoutOnlyFrame }

    /** The 30 independently admitted topologies, separate from operation selection. */
    public enum class Topology(
        public val axis: Axis,
        public val childCount: Int,
        public val shape: Shape,
        public val bounds: Bounds,
    ) {
        RowNone0(Axis.Row, 0, Shape.None, Bounds.Finite),
        RowNone1(Axis.Row, 1, Shape.None, Bounds.Finite),
        RowNone16(Axis.Row, 16, Shape.None, Bounds.Finite),
        RowNone128(Axis.Row, 128, Shape.None, Bounds.Finite),
        RowNone4096(Axis.Row, 4_096, Shape.None, Bounds.Finite),
        RowSparseEndsFill128Finite(Axis.Row, 128, Shape.SparseEndsFill, Bounds.Finite),
        RowSparseEndsFill128IntrinsicMain(Axis.Row, 128, Shape.SparseEndsFill, Bounds.IntrinsicMain),
        RowAlternatingFill128Finite(Axis.Row, 128, Shape.AlternatingFill, Bounds.Finite),
        RowAlternatingFill128IntrinsicMain(Axis.Row, 128, Shape.AlternatingFill, Bounds.IntrinsicMain),
        RowAllFill128Finite(Axis.Row, 128, Shape.AllFill, Bounds.Finite),
        RowAllFill128IntrinsicMain(Axis.Row, 128, Shape.AllFill, Bounds.IntrinsicMain),
        RowAllNonfill128Finite(Axis.Row, 128, Shape.AllNonfill, Bounds.Finite),
        RowAllNonfill128IntrinsicMain(Axis.Row, 128, Shape.AllNonfill, Bounds.IntrinsicMain),
        RowAllFill4096Finite(Axis.Row, 4_096, Shape.AllFill, Bounds.Finite),
        RowAllFill4096IntrinsicMain(Axis.Row, 4_096, Shape.AllFill, Bounds.IntrinsicMain),
        ColumnNone0(Axis.Column, 0, Shape.None, Bounds.Finite),
        ColumnNone1(Axis.Column, 1, Shape.None, Bounds.Finite),
        ColumnNone16(Axis.Column, 16, Shape.None, Bounds.Finite),
        ColumnNone128(Axis.Column, 128, Shape.None, Bounds.Finite),
        ColumnNone4096(Axis.Column, 4_096, Shape.None, Bounds.Finite),
        ColumnSparseEndsFill128Finite(Axis.Column, 128, Shape.SparseEndsFill, Bounds.Finite),
        ColumnSparseEndsFill128IntrinsicMain(Axis.Column, 128, Shape.SparseEndsFill, Bounds.IntrinsicMain),
        ColumnAlternatingFill128Finite(Axis.Column, 128, Shape.AlternatingFill, Bounds.Finite),
        ColumnAlternatingFill128IntrinsicMain(Axis.Column, 128, Shape.AlternatingFill, Bounds.IntrinsicMain),
        ColumnAllFill128Finite(Axis.Column, 128, Shape.AllFill, Bounds.Finite),
        ColumnAllFill128IntrinsicMain(Axis.Column, 128, Shape.AllFill, Bounds.IntrinsicMain),
        ColumnAllNonfill128Finite(Axis.Column, 128, Shape.AllNonfill, Bounds.Finite),
        ColumnAllNonfill128IntrinsicMain(Axis.Column, 128, Shape.AllNonfill, Bounds.IntrinsicMain),
        ColumnAllFill4096Finite(Axis.Column, 4_096, Shape.AllFill, Bounds.Finite),
        ColumnAllFill4096IntrinsicMain(Axis.Column, 4_096, Shape.AllFill, Bounds.IntrinsicMain),
        ;

        /** Positive scalar weight for this direct child, or absence of weight parent data. */
        public fun weight(index: Int): Float? =
            when (shape) {
                Shape.None -> {
                    null
                }

                Shape.SparseEndsFill -> {
                    when (index) {
                        0 -> 1f
                        childCount - 1 -> 3f
                        else -> null
                    }
                }

                Shape.AlternatingFill -> {
                    if (index % 2 == 0) (index % 3 + 1).toFloat() else null
                }

                Shape.AllFill, Shape.AllNonfill -> {
                    (index % 3 + 1).toFloat()
                }
            }
    }
}
