package dev.s7a.strata.runtime.remote

import dev.s7a.strata.element.Element
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.semantics.SemanticsRole
import java.util.Collections

/**
 * Explicit trusted client factories for negotiated component and modifier schemas.
 * Registration is complete before a connection captures [types]; peers can never supply factories or class names.
 * Property decoders validate untrusted values before invoking a typed factory.
 */
public class RemoteRegistry {
    private val elements = mutableMapOf<ProjectionType, (ProjectionValue) -> RemotePreparedTree.Component>()
    private val modifiers = mutableMapOf<ProjectionType, (ProjectionValue) -> RemotePreparedTree.ActiveModifier>()
    private val preparationIdentity = Any()
    private var frozen: Boolean = false
    private val roles = mutableMapOf<ProjectionType, SemanticsRole>()

    /**
     * Freezes registration and returns a detached immutable capability snapshot.
     */
    public val types: Set<ProjectionType>
        get() {
            frozen = true
            return Collections.unmodifiableSet((elements.keys + modifiers.keys + roles.keys).toSet())
        }

    /**
     * Registers one typed component decoder and factory before capability negotiation.
     */
    public fun <P : Any> element(
        type: ProjectionType,
        decode: (ProjectionValue) -> P,
        prepare: (P, RemotePreparationContext) -> Unit = NO_PREPARATION,
        phase: RemotePreparationPhase = RemotePreparationPhase.Owners,
        create: (P, RemoteElementContext) -> Element,
    ) {
        requireRegistration(type)
        elements[type] = { value ->
            val properties = decode(value)
            RemotePreparedTree.Component(phase, { context -> prepare(properties, context) }, { context -> create(properties, context) }, prepare !== NO_PREPARATION)
        }
    }

    /**
     * Registers one typed active modifier decoder and factory before capability negotiation.
     */
    public fun <P : Any> modifier(
        type: ProjectionType,
        decode: (ProjectionValue) -> P,
        create: (P, RemoteClientActions) -> Modifier,
    ) {
        statefulModifier(type, decode) { properties, context -> create(properties, context.actions) }
    }

    /**
     * Registers a modifier that shares prepared client state with components or other modifiers.
     * Preparation runs only after the entire incoming tree has passed decoding and outside declaration evaluation.
     */
    public fun <P : Any> statefulModifier(
        type: ProjectionType,
        decode: (ProjectionValue) -> P,
        prepare: (P, RemotePreparationContext) -> Unit = NO_PREPARATION,
        phase: RemotePreparationPhase = RemotePreparationPhase.References,
        create: (P, RemoteModifierContext) -> Modifier,
    ) {
        requireRegistration(type)
        modifiers[type] = { value ->
            val properties = decode(value)
            RemotePreparedTree.ActiveModifier(phase, { context -> prepare(properties, context) }, { context -> create(properties, context) }, prepare !== NO_PREPARATION)
        }
    }

    /**
     * Registers one immutable standard or application-defined accessibility role before negotiation.
     */
    public fun role(role: SemanticsRole) {
        val type = requireNotNull(role.projectionType) { "Remote roles require a projection type." }
        requireRegistration(type)
        roles[type] = role
    }

    /**
     * Resolves a previously registered role without accepting a peer-selected implementation class.
     */
    internal fun resolveRole(type: ProjectionType): SemanticsRole = requireNotNull(roles[type]) { "Unknown remote semantics role." }

    /**
     * Checks every component and modifier schema before a received tree can replace the current tree.
     */
    public fun validateTypes(tree: RemoteTree) {
        frozen = true
        tree.nodes.values.forEach { node ->
            require(node.declaration.type in elements) { "Unsupported remote component: ${node.declaration.type}." }
            node.modifiers.forEach { require(it.type in modifiers) { "Unsupported remote modifier: ${it.type}." } }
        }
    }

    /**
     * Validates and decodes all properties before a tree can become the client's committed declaration source.
     * Factories remain deferred until the installed component runtime evaluates the screen.
     */
    internal fun prepare(
        tree: RemoteTree,
        limits: RemoteLimits = RemoteLimits(),
        previous: RemotePreparedTree? = null,
    ): RemotePreparedTree {
        val budget = RemoteWorkBudget(limits)
        validateTypes(tree)
        // Only this frozen registry can certify old decoded factory captures.
        val trusted = previous?.takeIf { it.registryIdentity === preparationIdentity }
        val decoded =
            tree.nodes.mapValues { (identity, node) ->
                budget.visit()
                val old = trusted?.tree?.nodes?.get(identity)
                val cached = trusted?.decoded?.get(identity)
                val component = cached?.component?.takeIf { old?.declaration == node.declaration }
                    ?: elements.getValue(node.declaration.type)(node.declaration.value)
                val active = if (old?.modifiers == node.modifiers && cached != null) {
                    repeat(node.modifiers.size) { budget.visit() }
                    cached.modifiers
                } else {
                    val indices = old?.modifiers?.withIndex()?.associate { it.value.identity to it.index }.orEmpty()
                    node.modifiers.map { declaration ->
                        budget.visit()
                        val index = indices[declaration.identity]
                        val retained = index?.let { cached?.modifiers?.getOrNull(it) }
                        val previousDeclaration = index?.let { old?.modifiers?.get(it) }
                        retained?.takeIf { _ -> previousDeclaration == declaration }
                            ?: modifiers.getValue(declaration.type)(declaration.value)
                    }
                }
                if (cached != null && component === cached.component && active === cached.modifiers) {
                    cached
                } else {
                    RemotePreparedTree.Node(
                        component,
                        active,
                    )
                }
            }
        budget.checkTime()
        return RemotePreparedTree(tree, decoded, preparationIdentity)
    }

    /**
     * Shared pure default recognized only by reference; a caller callback remains observable even when it does nothing.
     */
    private companion object {
        val NO_PREPARATION: (Any, RemotePreparationContext) -> Unit = { _, _ -> }
    }

    private fun requireRegistration(type: ProjectionType) {
        check(frozen.not()) { "Remote registrations are frozen after capability negotiation." }
        require((type in elements).not() && (type in modifiers).not() && (type in roles).not()) { "Duplicate remote schema registration." }
    }
}
