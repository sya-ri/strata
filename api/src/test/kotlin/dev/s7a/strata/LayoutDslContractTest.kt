package dev.s7a.strata

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ColumnScope
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
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
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
        listOf(RowScope::class.java, ColumnScope::class.java, StackScope::class.java, GridScope::class.java).forEach { scopeClass ->
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
        listOf(UiScope::class.java, RowScope::class.java, ColumnScope::class.java, StackScope::class.java, GridScope::class.java)
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
            listOf<(UiScope) -> Unit>(
                { scope -> scope.Row { } },
                { scope -> scope.Column { } },
                { scope -> scope.Stack { } },
                { scope -> scope.Grid(columns = 1) { } },
                { scope -> scope.Spacer() },
            )
        builders.forEach { builder ->
            var capturedScope: UiScope? = null
            buildComponentTree {
                capturedScope = this
                Row { }
            }
            val failure = AtomicReference<Throwable?>(null)
            val worker =
                Thread {
                    try {
                        builder(requireNotNull(capturedScope))
                    } catch (error: Throwable) {
                        failure.set(error)
                    }
                }
            worker.start()
            worker.join(5_000)
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
        buildComponentTree { Row { rowScope = this } }
        buildComponentTree { Column { columnScope = this } }
        buildComponentTree { Stack { stackScope = this } }
        buildComponentTree { Grid(columns = 1) { gridScope = this } }

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
    }
}
