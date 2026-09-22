package dev.s7a.strata.element

import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.projection.DeclarationProjection

/**
 * Immutable description of one retained node and its direct children.
 *
 * The engine owns the description snapshot after construction.
 *
 * Element implementations keep local properties and provide their typed hooks through the stable [Element.type] token.
 *
 * @property identity positional or keyed identity under the parent.
 * @property type stable element-kind token.
 * @property children detached read-only snapshot of direct child descriptions.
 * @property modifier immutable active modifier descriptions applied to this component.
 */
public abstract class Element public constructor(
    public val identity: ElementIdentity,
    public val type: ElementType<*, *>,
    children: List<Element> = emptyList(),
    public val modifier: Modifier = Modifier.Empty,
) {
    /**
     * Optional typed declaration export used by remote runtimes.
     * The default keeps existing local-only elements independent of networking.
     */
    public open val projection: DeclarationProjection<*>? get() = null

    public val children: List<Element> = children.toList()
}
