package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies detached native focus snapshots and rejection of retired semantic identities.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeSemanticsFocusTest {
    @Test
    fun focusChangesRefreshFramesWithoutActivatingAndRetiredOwnersAreIgnored() {
        val visible = mutableStateOf(true)
        var actions = 0
        createRuntimeUiSession {
            evaluateComponentTree {
                Column {
                    for (id in if (visible.value) listOf(1, 2) else listOf(2)) {
                        Stack(Modifier.size(40, 20).semantics(Semantics(UiText.Literal(id.toString()), SemanticsRole.Button)).onActivate { actions += 1 }, key = ElementKey(id)) {
                            Spacer(Modifier.size(40, 20))
                        }
                    }
                }
            }
        }.use { session ->
            session.attach()
            val before = session.frame(Constraints.fixed(80, 80))
            val owner = assertNotNull(before.semantics.first().owner)
            assertTrue(owner.focusable)
            assertFalse(owner.focused)
            assertEquals(InputResult.Ignored, session.focusSemantics(0))
            assertEquals(InputResult.Consumed, session.focusSemantics(owner.identity))
            val focused = session.frame(Constraints.fixed(80, 80))
            assertEquals(
                owner.identity,
                focused.semantics
                    .first()
                    .owner
                    ?.identity,
            )
            assertEquals(
                true,
                focused.semantics
                    .first()
                    .owner
                    ?.focused,
            )
            assertFalse(owner.focused)
            assertEquals(0, actions)
            visible.value = false
            session.frame(Constraints.fixed(80, 80))
            assertEquals(InputResult.Ignored, session.focusSemantics(owner.identity))
            assertEquals(0, actions)
        }
    }
}
