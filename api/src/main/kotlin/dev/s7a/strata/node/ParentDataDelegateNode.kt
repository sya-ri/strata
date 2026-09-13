package dev.s7a.strata.node

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Allows a transparent retained wrapper to forward parent-data lookup to one logical child.
 * Layout and event modifiers remain on that child; the runtime forwards lookup without copying or executing them twice.
 */
@InternalStrataRuntimeApi
public interface ParentDataDelegateNode {
    /**
     * Child index used for parent-data lookup, or null to preserve the ordinary component boundary.
     */
    public val parentDataChild: Int?
}
