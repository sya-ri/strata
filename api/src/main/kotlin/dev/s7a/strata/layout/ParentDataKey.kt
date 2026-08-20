package dev.s7a.strata.layout

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.jvm.javaObjectType
import kotlin.reflect.KClass

/**
 * Referential token identifying one parent-data value type.
 *
 * Key identity is the key instance itself, so two keys constructed with the same [dataClass] remain distinct contracts.
 * The class token is immutable for the lifetime of the key and is owned by the code that defines the corresponding provider and consumer.
 *
 * The runtime bridge validates an erased provider value against [dataClass] and returns the checked value.
 * A value of the wrong runtime class fails with [IllegalArgumentException].
 *
 * @param D the parent-data value type represented by this key.
 * @param dataClass the immutable runtime type token used for erased-boundary validation.
 */
public class ParentDataKey<D : Any> public constructor(
    private val dataClass: KClass<D>,
) {
    /**
     * Validates and casts one erased provider value for this key.
     *
     * The runtime invokes the selected provider and then calls this narrow bridge.
     * This function uses this key's class token to validate and cast the value without changing it.
     *
     * @param value the current provider value at the erased runtime boundary.
     * @return the value checked against this key's runtime type.
     * @throws IllegalArgumentException when [value] is outside this key's runtime type.
     */
    @InternalStrataRuntimeApi
    public fun castErased(value: Any): D {
        require(dataClass.isInstance(value)) { "Parent data provider returned the wrong runtime type." }
        return dataClass.javaObjectType.cast(value)
    }
}
