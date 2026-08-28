package dev.s7a.strata

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ColumnScope
import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.component.FlowRowScope
import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.GridScope
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.RowScope
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.StackScope
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.buildComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.FlowRowAlignmentParentData
import dev.s7a.strata.layout.FlowRowElement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.padding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import java.lang.reflect.Modifier as JavaModifier

/**
 * Verifies callback-lifetime behavior of built-in component scopes.
 */
internal class LayoutDslContractTest {
    @Test
    fun flowRowBuildersPreserveDefaultsOptionsAndDirectChildren() {
        val defaults = buildComponentTree { FlowRow { } } as FlowRowElement
        assertEquals(0, defaults.horizontalSpacing)
        assertEquals(0, defaults.verticalSpacing)
        assertEquals(Arrangement.Start, defaults.horizontalArrangement)
        assertEquals(VerticalAlignment.Top, defaults.verticalAlignment)
        assertEquals(ElementIdentity.Positional, defaults.identity)
        assertSame(Modifier.Empty, defaults.modifier)

        val first = buildComponentTree { Spacer() }
        val second = buildComponentTree { Spacer() }
        val key = ElementKey("flow")
        val modifier = Modifier.Empty.padding(2)
        val configured =
            buildComponentTree {
                FlowRow(
                    modifier = modifier,
                    key = key,
                    horizontalSpacing = 3,
                    verticalSpacing = 5,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = VerticalAlignment.Bottom,
                ) {
                    element(first)
                    element(second)
                }
            } as FlowRowElement

        assertEquals(3, configured.horizontalSpacing)
        assertEquals(5, configured.verticalSpacing)
        assertEquals(Arrangement.SpaceBetween, configured.horizontalArrangement)
        assertEquals(VerticalAlignment.Bottom, configured.verticalAlignment)
        assertEquals(ElementIdentity.Keyed(key), configured.identity)
        assertSame(modifier, configured.modifier)
        assertEquals(listOf(first, second), configured.children)
    }

    @Test
    fun flowRowScopeAppendsDedicatedAlignmentInsideExistingModifiers() {
        val outer = Modifier.Empty.padding(2)
        val root =
            buildComponentTree {
                FlowRow {
                    Spacer(outer.align(VerticalAlignment.Bottom).align(VerticalAlignment.Center))
                }
            }

        assertEquals(
            outer
                .then(FlowRowAlignmentParentData.Element(FlowRowAlignmentParentData.Data(VerticalAlignment.Bottom)))
                .then(FlowRowAlignmentParentData.Element(FlowRowAlignmentParentData.Data(VerticalAlignment.Center))),
            root.children.single().modifier,
        )
    }

    @Test
    fun childScopeClosesAfterCallbackFailureAndPreservesFailureIdentity() {
        val callbackFailure = IllegalStateException("layout callback failure")
        var capturedScope: RowScope? = null

        val propagated =
            assertThrows(IllegalStateException::class.java) {
                buildComponentTree {
                    Row {
                        capturedScope = this
                        throw callbackFailure
                    }
                }
            }

        assertSame(callbackFailure, propagated)
        val scope = requireNotNull(capturedScope)
        assertThrows(IllegalStateException::class.java) {
            with(scope) {
                Modifier.Empty.weight(1f)
            }
        }
    }

    @Test
    fun flowRowScopeClosesAfterCallbackFailureAndPreservesFailureIdentity() {
        val callbackFailure = IllegalStateException("flow row callback failure")
        var capturedScope: FlowRowScope? = null

        val propagated =
            assertThrows(IllegalStateException::class.java) {
                buildComponentTree {
                    FlowRow {
                        capturedScope = this
                        Spacer()
                        throw callbackFailure
                    }
                }
            }

        assertSame(callbackFailure, propagated)
        val scope = requireNotNull(capturedScope)
        assertThrows(IllegalStateException::class.java) {
            with(scope) { Modifier.Empty.align(VerticalAlignment.Bottom) }
        }
        assertThrows(IllegalStateException::class.java) { scope.Spacer() }
    }

    @Test
    fun escapedOuterComponentRejectsWrongThreadBeforeRunningContent() {
        var capturedScope: UiScope? = null
        buildComponentTree {
            capturedScope = this
            Row { }
        }
        val scope = requireNotNull(capturedScope)
        val contentRan = AtomicReference(false)
        val failure = AtomicReference<Throwable?>(null)
        val worker =
            Thread {
                try {
                    scope.Row { contentRan.set(true) }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        worker.start()
        worker.join(5_000)

        assertFalse(contentRan.get())
        assertEquals(
            "UiScope can only be used from its constructing thread.",
            requireNotNull(failure.get()).message,
        )
    }

    @Test
    fun publicScopesHaveNoPublicJavaConstructor() {
        listOf(RowScope::class.java, ColumnScope::class.java, StackScope::class.java, GridScope::class.java, FlowRowScope::class.java).forEach { scopeClass ->
            assertTrue(JavaModifier.isAbstract(scopeClass.modifiers))
            assertTrue(scopeClass.isSealed)
            assertTrue(
                scopeClass.constructors.none { constructor -> constructor.isSynthetic.not() },
            )
            assertTrue(
                scopeClass.declaredConstructors
                    .filter { constructor -> constructor.isSynthetic.not() }
                    .all { constructor -> JavaModifier.isPrivate(constructor.modifiers) },
            )
        }
        listOf(UiScope::class.java, RowScope::class.java, ColumnScope::class.java, StackScope::class.java, GridScope::class.java, FlowRowScope::class.java)
            .forEach(::assertNoJavaFactory)
        val intendedElementMethod = UiScope::class.java.getDeclaredMethod("element", Element::class.java)
        assertEquals(
            listOf(intendedElementMethod),
            UiScope::class.java.declaredMethods.filter { method ->
                JavaModifier.isPublic(method.modifiers) && method.isSynthetic.not()
            },
        )
    }

    private fun assertNoJavaFactory(scopeClass: Class<out UiScope>) {
        assertTrue(
            scopeClass.methods.none { method ->
                scopeClass.isAssignableFrom(method.returnType) && method.isSynthetic.not()
            },
        )
        val companionField = scopeClass.getDeclaredField("Companion")
        val companion = companionField.get(null)
        assertTrue(
            companion::class.java.methods.none { method ->
                scopeClass.isAssignableFrom(method.returnType) && method.isSynthetic.not()
            },
        )
    }

    @Test
    fun everyComponentBuilderRejectsWrongThreadBeforeItsCallback() {
        val builders =
            listOf<(UiScope, () -> Unit) -> Unit>(
                { scope, callback -> scope.Row { callback() } },
                { scope, callback -> scope.Column { callback() } },
                { scope, callback -> scope.Stack { callback() } },
                { scope, callback -> scope.Grid(columns = 1) { callback() } },
                { scope, callback -> scope.FlowRow { callback() } },
                { scope, _ -> scope.Spacer() },
            )
        builders.forEach { builder ->
            var capturedScope: UiScope? = null
            buildComponentTree {
                capturedScope = this
                Row { }
            }
            val failure = AtomicReference<Throwable?>(null)
            val contentRan = AtomicReference(false)
            val worker =
                Thread {
                    try {
                        builder(requireNotNull(capturedScope)) { contentRan.set(true) }
                    } catch (error: Throwable) {
                        failure.set(error)
                    }
                }
            worker.start()
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertFalse(contentRan.get())
            assertEquals(
                "UiScope can only be used from its constructing thread.",
                requireNotNull(failure.get()).message,
            )
        }
    }

    @Test
    fun everyScopeRejectsLateModifierBehavior() {
        var rowScope: RowScope? = null
        var columnScope: ColumnScope? = null
        var stackScope: StackScope? = null
        var gridScope: GridScope? = null
        var flowRowScope: FlowRowScope? = null
        buildComponentTree { Row { rowScope = this } }
        buildComponentTree { Column { columnScope = this } }
        buildComponentTree { Stack { stackScope = this } }
        buildComponentTree { Grid(columns = 1) { gridScope = this } }
        buildComponentTree { FlowRow { flowRowScope = this } }

        assertThrows(IllegalStateException::class.java) {
            with(requireNotNull(rowScope)) { Modifier.Empty.weight(1f) }
        }
        assertThrows(IllegalStateException::class.java) {
            with(requireNotNull(columnScope)) { Modifier.Empty.weight(1f) }
        }
        assertThrows(IllegalStateException::class.java) {
            with(requireNotNull(stackScope)) { Modifier.Empty.align(Alignment.Center) }
        }
        assertThrows(IllegalStateException::class.java) {
            with(requireNotNull(gridScope)) { Modifier.Empty.align(Alignment.Center) }
        }
        assertThrows(IllegalStateException::class.java) {
            with(requireNotNull(flowRowScope)) { Modifier.Empty.align(VerticalAlignment.Center) }
        }
    }
}
