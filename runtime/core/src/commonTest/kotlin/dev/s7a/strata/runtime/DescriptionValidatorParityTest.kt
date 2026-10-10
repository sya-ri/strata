@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Independent old-algorithm comparison for every key and validation-order boundary, on both backends.
 */
internal class DescriptionValidatorParityTest {
    @Test
    fun c01KeylessLeaf() = compare { listOf(it.element(0, modifier = Modifier.Empty.then(it.modifier(0)))) }

    @Test
    fun c02KeylessWide() = compare { probe -> listOf(probe.element(0, List(128) { probe.element(it + 1) })) }

    @Test
    fun c03KeylessChainGroups() = compare { probe ->
        listOf(probe.element(0, List(8) { group -> (16 downTo 1).fold(emptyList<Element>()) { children, depth -> listOf(probe.element(group * 16 + depth, children)) }.single() }))
    }

    @Test
    fun c04KeylessBalanced() = compare { probe ->
        val elements = arrayOfNulls<Element>(129)
        for (id in 128 downTo 0) {
            val children = (id * 2 + 1..id * 2 + 2).filter { it <= 128 }.map { checkNotNull(elements[it]) }
            elements[id] = probe.element(id, children)
        }
        listOf(checkNotNull(elements[0]))
    }

    @Test
    fun c05EmptyDynamicSiblings() = compare { emptyList() }

    @Test
    fun c06OneKeyedChild() = compare { listOf(it.element(1, key = it.key(1))) }

    @Test
    fun c07AllDistinctKeys() = compare { probe -> List(16) { probe.element(it, key = probe.key(it, hash = it)) } }

    @Test
    fun c08MixedKeys() = compare { probe -> List(16) { probe.element(it, key = if (it % 2 == 0) probe.key(it) else null) } }

    @Test
    fun c09SparseLeadingKey() = compare { probe -> List(16) { probe.element(it, key = if (it == 0) probe.key(it) else null) } }

    @Test
    fun c10SparseTrailingKey() = compare { probe -> List(16) { probe.element(it, key = if (it == 15) probe.key(it) else null) } }

    @Test
    fun c11EqualDistinctKeyObjects() = compare(rejects = true) { listOf(it.element(1, key = it.key(1, value = 0)), it.element(2, key = it.key(2, value = 0))) }

    @Test
    fun c12RepeatedSameKeyObject() = compare(rejects = true) { probe ->
        val key = probe.key(1)
        listOf(probe.element(1, key = key), probe.element(2, key = key))
    }

    @Test
    fun c13HashCollisionsDistinct() = compare { probe -> List(16) { probe.element(it, key = probe.key(it)) } }

    @Test
    fun c14SameKeyDifferentParents() = compare { probe ->
        listOf(probe.element(0, List(2) { probe.element(it + 1, listOf(probe.element(it + 3, key = probe.key(it, value = 0)))) }))
    }

    @Test
    fun c15HashThrows() {
        val failure = IllegalStateException("hash failure")
        compare(failure) { listOf(it.element(1, key = it.key(1, hashFailure = failure))) }
    }

    @Test
    fun c16EqualityThrows() {
        val failure = IllegalStateException("equality failure")
        compare(failure) { listOf(it.element(1, key = it.key(1, equalityFailure = failure)), it.element(2, key = it.key(2, equalityFailure = failure))) }
    }

    @Test
    fun c17HashTrace() = compare { probe -> List(8) { probe.element(it, key = probe.key(it, hash = it * 31)) } }

    @Test
    fun c18EqualityTrace() = compare { probe -> List(8) { probe.element(it, key = probe.key(it)) } }

    @Test
    fun c19ElementValidationOrder() = compare { probe -> listOf(probe.element(0, listOf(probe.element(1, listOf(probe.element(2))), probe.element(3)))) }

    @Test
    fun c20ModifierValidationOrder() = compare { probe ->
        listOf(probe.element(0, listOf(probe.element(1, modifier = Modifier.Empty.then(probe.modifier(3)))), modifier = Modifier.Empty.then(probe.modifier(1)).then(probe.modifier(2))))
    }

    @Test
    fun c21ElementValidationFailure() {
        val failure = IllegalStateException("local failure")
        compare(failure) { listOf(it.element(1, validate = { throw failure })) }
    }

    @Test
    fun c22ModifierValidationFailure() {
        val failure = IllegalStateException("modifier failure")
        compare(failure) { listOf(it.element(1, modifier = Modifier.Empty.then(it.modifier(1, validate = { throw failure })))) }
    }

    @Test
    fun c23DuplicateBeforeDescendantFailure() = compare(rejects = true) { probe ->
        listOf(probe.element(1, key = probe.key(1, value = 0)), probe.element(2, key = probe.key(2, value = 0), validate = { error("unreached duplicate child") }))
    }

    @Test
    fun c24EarlierInvalidSiblingBeforeDuplicate() {
        val failure = IllegalStateException("earlier local failure")
        compare(failure) { probe ->
            listOf(probe.element(1, key = probe.key(1, value = 0), validate = { throw failure }), probe.element(2, key = probe.key(2, value = 0)))
        }
    }

    @Test
    fun c31SharedPositionalDescription() = compare { probe ->
        val shared = probe.element(3)
        listOf(probe.element(0, listOf(probe.element(1, listOf(shared)), probe.element(2, listOf(shared)))))
    }

    /**
     * Replays immutable descriptions through independent algorithms and compares complete traces at both sibling entry points.
     * Reusing the same key objects fixes identity-sensitive collision ordering between executions.
     */
    private fun compare(
        exactFailure: Throwable? = null,
        rejects: Boolean = exactFailure != null,
        build: (DescriptionValidationProbe) -> List<Element>,
    ) {
        for (dynamic in listOf(false, true)) {
            val probe = DescriptionValidationProbe()
            val children = build(probe)
            val root = probe.element(-1, children)
            val expected =
                runCatching {
                    if (dynamic) OriginalDescriptionValidator().validateChildren(children) else OriginalDescriptionValidator().validate(root)
                }.exceptionOrNull()
            val expectedTrace = probe.trace.toList()
            probe.trace.clear()
            val actual =
                runCatching {
                    if (dynamic) DescriptionValidator().validateChildren(children) else DescriptionValidator().validate(root)
                }.exceptionOrNull()
            assertEquals(expectedTrace, probe.trace)
            assertEquals(rejects, actual != null)
            assertEquals(expected?.let { it::class }, actual?.let { it::class })
            assertEquals(expected?.message, actual?.message)
            if (exactFailure != null) {
                assertSame(exactFailure, expected)
                assertSame(exactFailure, actual)
            }
            if (actual == null && children.isNotEmpty()) assertTrue(expectedTrace.any { it is DescriptionValidationProbe.Event.Local })
        }
    }
}
