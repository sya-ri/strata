package dev.s7a.strata.runtime.remote

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.modifier.Modifier
import java.util.Collections

/**
 * Complete decoded surroundings supplied to a trusted component factory.
 * Factories use [key] and [modifier] unchanged so remote identity and modifier order survive reconstruction.
 */
public class RemoteElementContext internal constructor(
    public val identity: Long,
    public val modifier: Modifier,
    children: List<Element>,
    public val actions: RemoteClientActions,
    public val states: RemoteClientStates,
) {
    public val key: ElementKey<Long> = ElementKey(identity)
    public val children: List<Element> = Collections.unmodifiableList(children.toList())
}
