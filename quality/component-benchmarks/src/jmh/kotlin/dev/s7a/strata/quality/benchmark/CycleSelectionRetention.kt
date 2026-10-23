package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Inspects the actual reachable host and cycle node during untimed replacement and terminal admission.
 * Reflection stays in this unpublished fixture and requires every named ownership slot to exist.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class CycleSelectionRetention(
    private val host: MinecraftUiHost,
    private val previous: Any,
    private val replacement: Any,
) {
    private val session = checkNotNull(field(checkNotNull(field(host, "session")), "session"))
    private val tree = checkNotNull(field(session, "tree"))
    private val node = nodes(checkNotNull(field(tree, "root"))).single(::isCycleButton)

    /**
     * Requires the retained node to transfer ownership and stop observing the old state.
     */
    fun verifyReplacement() {
        check(field(node, "state") === replacement)
        checkNotNull(field(node, "observer"))
        // Only this fixture's explicit observer remains on the replaced state.
        check(observers(previous).size == 1)
    }

    /**
     * Requires all application captures, retained tree ownership, labels, sprites and subscriptions to be released.
     */
    fun verifyClosed() {
        for (name in listOf("state", "labels", "selectedValue", "observer", "normalSprite", "highlightedSprite", "disabledSprite")) {
            check(field(node, name) == null) { "Closed cycle node retained $name" }
        }
        check(field(tree, "root") == null)
        check(field(session, "tree") == null && field(session, "retainedContent") == null)
        for (name in listOf("evaluator", "resourceEvaluator", "platform", "textRenderer", "metadata")) check(field(host, name) == null)
        check(observers(previous).isEmpty() && observers(replacement).isEmpty())
    }

    private fun isCycleButton(node: Any): Boolean {
        val values = mutableListOf<Semantics>()
        (node as? SemanticsNode)?.semantics(
            object : SemanticsScope {
                override fun emit(semantics: Semantics) {
                    values.add(semantics)
                }
            },
        )
        return values.any { it.role == SemanticsRole.CycleButton }
    }

    private fun observers(state: Any): Set<*> = field(checkNotNull(field(state, "observable")), "observers") as Set<*>

    private fun nodes(entry: Any): List<Any> = listOf(checkNotNull(field(entry, "node"))) + (field(entry, "children") as List<*>).flatMap { nodes(checkNotNull(it)) }

    private fun field(
        owner: Any,
        name: String,
    ): Any? {
        val declaration =
            generateSequence(owner.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .single { it.name == name }
        check(declaration.trySetAccessible())
        return declaration.get(owner)
    }
}
