@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.spi.RuntimeUiControl
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiScreenKind
import dev.s7a.strata.ui.UiVisibility
import dev.s7a.strata.ui.UiVisibilityPolicy

/**
 * Version-one message schemas layered on the bounded value codec.
 * All external discriminators are decoded into typed messages before dispatch.
 */
@Suppress("TooManyFunctions") // Keeps both directions of the complete wire schema together.
public class RemoteMessageCodec(
    private val limits: RemoteLimits = RemoteLimits(),
) {
    private val values = RemoteValueCodec(limits)

    /**
     * Encodes a complete logical message before transport fragmentation.
     */
    public fun encode(message: RemoteMessage): ByteArray = values.encode(project(message))

    /**
     * Decodes and validates the complete message schema and any included tree.
     */
    public fun decode(bytes: ByteArray): RemoteMessage {
        val fields = ProjectionFields(values.decode(bytes))
        val code = fields.int()
        val tag = requireNotNull(Tag.entries.find { it.code == code }) { "Unknown remote message tag." }
        val message =
            when (tag) {
                Tag.Hello -> {
                    RemoteMessage.Hello(fields.int(1..Int.MAX_VALUE), readLimits(fields.value()), fields.values().map(::readType).toSet())
                }

                Tag.Snapshot -> {
                    RemoteMessage.Snapshot(positive(fields.long()), positive(fields.long()), fields.value(), readTree(fields.value()), fields.flag(), readSettings(fields.value()), readOptionalControl(fields.value()))
                }

                Tag.Control -> {
                    RemoteMessage.Control(positive(fields.long()), readControl(fields.value()))
                }

                Tag.ControlRequest -> {
                    RemoteMessage.ControlRequest(positive(fields.long()), readControl(fields.value()))
                }

                Tag.ControlApplied -> {
                    RemoteMessage.ControlApplied(positive(fields.long()), positive(fields.long()), readRejection(fields))
                }

                Tag.ControlReceipt -> {
                    RemoteMessage.ControlReceipt(positive(fields.long()), positive(fields.long()), readRejection(fields))
                }

                Tag.Update -> {
                    val session = positive(fields.long())
                    val base = positive(fields.long())
                    val revision = positive(fields.long())
                    require(base < revision) { "A remote update must advance the revision." }
                    RemoteMessage.Update(session, base, revision, readPatch(fields.value()))
                }

                Tag.Action -> {
                    RemoteMessage.Action(positive(fields.long()), positive(fields.long()), positive(fields.long()), readType(fields.value()), fields.value())
                }

                Tag.Acknowledgement -> {
                    RemoteMessage.Acknowledgement(positive(fields.long()), positive(fields.long()), positive(fields.long()))
                }

                Tag.Applied -> {
                    RemoteMessage.Applied(positive(fields.long()), positive(fields.long()))
                }

                Tag.Resynchronize -> {
                    RemoteMessage.Resynchronize(positive(fields.long()))
                }

                Tag.Close -> {
                    RemoteMessage.Close(positive(fields.long()), RemoteFailure.entries[fields.int(RemoteFailure.entries.indices)])
                }
            }
        fields.finish()
        return message
    }

    private fun project(message: RemoteMessage): ProjectionValue =
        when (message) {
            is RemoteMessage.Hello -> fields(number(Tag.Hello.code), number(message.protocol), projectLimits(message.limits), sequence(message.types.map(::projectType)))
            is RemoteMessage.Snapshot -> projectSnapshot(message)
            is RemoteMessage.Control -> fields(number(Tag.Control.code), number(message.session), projectControl(message.state))
            is RemoteMessage.ControlRequest -> fields(number(Tag.ControlRequest.code), number(message.session), projectControl(message.state))
            is RemoteMessage.ControlApplied -> fields(number(Tag.ControlApplied.code), number(message.session), number(message.sequence), projectRejection(message.rejection))
            is RemoteMessage.ControlReceipt -> fields(number(Tag.ControlReceipt.code), number(message.session), number(message.sequence), projectRejection(message.rejection))
            is RemoteMessage.Update -> fields(number(Tag.Update.code), number(message.session), number(message.baseRevision), number(message.revision), projectPatch(message.patch))
            is RemoteMessage.Action -> fields(number(Tag.Action.code), number(message.session), number(message.sequence), number(message.endpoint), projectType(message.type), message.value)
            is RemoteMessage.Acknowledgement -> fields(number(Tag.Acknowledgement.code), number(message.session), number(message.sequence), number(message.revision))
            is RemoteMessage.Applied -> fields(number(Tag.Applied.code), number(message.session), number(message.revision))
            is RemoteMessage.Resynchronize -> fields(number(Tag.Resynchronize.code), number(message.session))
            is RemoteMessage.Close -> fields(number(Tag.Close.code), number(message.session), number(message.reason.ordinal))
        }

    private fun projectSnapshot(message: RemoteMessage.Snapshot): ProjectionValue = fields(number(Tag.Snapshot.code), number(message.session), number(message.revision), message.title, projectTree(message.tree), ProjectionValue.Flag(message.pausesGame), projectSettings(message.settings), message.control?.let(::projectControl) ?: ProjectionValue.Absent)

    private fun projectRejection(rejection: UiRejection?): ProjectionValue = number(rejection?.ordinal ?: -1)

    private fun readRejection(fields: ProjectionFields): UiRejection? {
        val index = fields.int(-1..UiRejection.entries.lastIndex)
        return if (index < 0) null else UiRejection.entries[index]
    }

    private fun projectInput(value: UiInputPolicy): ProjectionValue = sequence(listOf(value.movement, value.jump, value.sneak, value.sprint, value.look, value.attack, value.use, value.hotbar, value.drop, value.swapHands, value.pick).map(ProjectionValue::Flag))

    private fun readInput(value: ProjectionValue): UiInputPolicy =
        read(value) {
            UiInputPolicy(flag(), flag(), flag(), flag(), flag(), flag(), flag(), flag(), flag(), flag(), flag())
        }

    private fun projectCategory(category: UiCategory?): ProjectionValue = category?.let { fields(ProjectionValue.Text(it.id.namespace), ProjectionValue.Text(it.id.path)) } ?: ProjectionValue.Absent

    private fun readCategory(value: ProjectionValue): UiCategory? = if (value === ProjectionValue.Absent) null else read(value) { UiCategory(ResourceId(text(), text())) }

    private fun projectSettings(value: RemoteUiSettings): ProjectionValue =
        fields(
            number(value.presentation.ordinal),
            projectCategory(value.category),
            projectInput(value.inputPolicy),
            number(value.hudOrder),
            number(value.visibility.default.ordinal),
            sequence(value.visibility.categories.map { (category, action) -> fields(projectCategory(category), number(action.ordinal)) }),
            sequence(value.visibility.screens.map { (kind, action) -> fields(number(kind.ordinal), number(action.ordinal)) }),
        )

    private fun readSettings(value: ProjectionValue): RemoteUiSettings =
        read(value) {
            val presentation = UiPresentation.entries[int(UiPresentation.entries.indices)]
            val category = readCategory(value())
            val input = readInput(value())
            val order = int()
            val default = UiVisibility.entries[int(UiVisibility.entries.indices)]
            val categories = values().associate { entry -> read(entry) { checkNotNull(readCategory(value())) to UiVisibility.entries[int(UiVisibility.entries.indices)] } }
            val screens = values().associate { entry -> read(entry) { UiScreenKind.entries[int(UiScreenKind.entries.indices)] to UiVisibility.entries[int(UiVisibility.entries.indices)] } }
            RemoteUiSettings(presentation, category, input, UiVisibilityPolicy(default, categories, screens), order)
        }

    private fun projectControl(value: RuntimeUiControl): ProjectionValue = fields(number(value.sequence), number(value.presentation.ordinal), projectInput(value.inputPolicy), number(value.interactionMode.ordinal))

    private fun readControl(value: ProjectionValue): RuntimeUiControl =
        read(value) {
            RuntimeUiControl(positive(long()), UiPresentation.entries[int(UiPresentation.entries.indices)], readInput(value()), UiInteractionMode.entries[int(UiInteractionMode.entries.indices)])
        }

    private fun readOptionalControl(value: ProjectionValue): RuntimeUiControl? = if (value === ProjectionValue.Absent) null else readControl(value)

    private fun projectType(type: ProjectionType): ProjectionValue = fields(ProjectionValue.Text(type.name.namespace), ProjectionValue.Text(type.name.path), number(type.version))

    private fun readType(value: ProjectionValue): ProjectionType =
        read(value) {
            ProjectionType(ResourceId(text(), text()), int(1..Int.MAX_VALUE))
        }

    private fun projectLimits(value: RemoteLimits): ProjectionValue = fields(number(value.frameBytes), number(value.messageBytes), number(value.valueDepth), number(value.collectionEntries), number(value.treeNodes), number(value.pendingBytes), number(value.assemblyMillis), number(value.valueEntries), number(value.reconstructionMillis), number(value.hudSessions))

    private fun readLimits(value: ProjectionValue): RemoteLimits =
        read(value) {
            RemoteLimits(int(), int(), int(), int(), int(), int(), long(), int(), long(), int())
        }

    private fun projectDeclaration(value: RemoteDeclaration): ProjectionValue = fields(number(value.identity), projectType(value.type), value.value)

    private fun readDeclaration(value: ProjectionValue): RemoteDeclaration =
        read(value) {
            RemoteDeclaration(positive(long()), readType(value()), value())
        }

    private fun projectNode(value: RemoteNode): ProjectionValue = fields(projectDeclaration(value.declaration), sequence(value.modifiers.map(::projectDeclaration)), sequence(value.children.map(::number)))

    private fun readNode(value: ProjectionValue): RemoteNode =
        read(value) {
            RemoteNode(readDeclaration(value()), values().map(::readDeclaration), values().map { field -> positive(requireNotNull(field as? ProjectionValue.Integer).value) })
        }

    private fun projectTree(value: RemoteTree): ProjectionValue = fields(number(value.root), sequence(value.nodes.values.map(::projectNode)))

    private fun readTree(value: ProjectionValue): RemoteTree = read(value) { RemoteTree(positive(long()), values().map(::readNode), limits) }

    private fun projectPatch(value: RemotePatch): ProjectionValue = fields(number(value.root), sequence(value.changed.map(::projectNode)), sequence(value.removed.map(::number)))

    private fun readPatch(value: ProjectionValue): RemotePatch =
        read(value) {
            RemotePatch(positive(long()), values().map(::readNode), values().map { field -> positive(requireNotNull(field as? ProjectionValue.Integer).value) })
        }

    private fun <T> read(
        value: ProjectionValue,
        block: ProjectionFields.() -> T,
    ): T {
        val fields = ProjectionFields(value)
        val result = fields.block()
        fields.finish()
        return result
    }

    private fun positive(value: Long): Long {
        require(0L < value) { "Remote identities, revisions, and sequences must be positive." }
        return value
    }

    private fun number(value: Long): ProjectionValue = ProjectionValue.Integer(value)

    private fun number(value: Int): ProjectionValue = number(value.toLong())

    private fun fields(vararg values: ProjectionValue): ProjectionValue = sequence(values.toList())

    private fun sequence(values: List<ProjectionValue>): ProjectionValue = ProjectionValue.Sequence(values)

    /**
     * Stable protocol-one message tags.
     */
    private enum class Tag(
        val code: Int,
    ) {
        Hello(1),
        Snapshot(2),
        Update(3),
        Action(4),
        Acknowledgement(5),
        Resynchronize(6),
        Close(7),
        Applied(8),
        Control(9),
        ControlApplied(10),
        ControlRequest(11),
        ControlReceipt(12),
    }
}
