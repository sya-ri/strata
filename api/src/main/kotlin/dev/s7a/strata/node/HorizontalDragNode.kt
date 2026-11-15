package dev.s7a.strata.node

/**
 * A captured horizontal control that yields vertical movement to an ancestor [DragScrollNode].
 * Inside a scroll viewport, the runtime delays its press until a stationary tap or horizontal drag is recognized.
 * Horizontal dragging then uses ordinary pointer capture, while a vertical drag never activates the control.
 * Outside a scroll viewport, ordinary immediate pointer dispatch is preserved.
 */
public interface HorizontalDragNode : PointerCaptureNode
