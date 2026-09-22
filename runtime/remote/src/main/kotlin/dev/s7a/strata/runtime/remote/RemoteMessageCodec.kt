package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId

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
                    RemoteMessage.Snapshot(positive(fields.long()), positive(fields.long()), fields.value(), readTree(fields.value()), fields.flag())
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
            is RemoteMessage.Snapshot -> fields(number(Tag.Snapshot.code), number(message.session), number(message.revision), message.title, projectTree(message.tree), ProjectionValue.Flag(message.pausesGame))
            is RemoteMessage.Update -> fields(number(Tag.Update.code), number(message.session), number(message.baseRevision), number(message.revision), projectPatch(message.patch))
            is RemoteMessage.Action -> fields(number(Tag.Action.code), number(message.session), number(message.sequence), number(message.endpoint), projectType(message.type), message.value)
            is RemoteMessage.Acknowledgement -> fields(number(Tag.Acknowledgement.code), number(message.session), number(message.sequence), number(message.revision))
            is RemoteMessage.Applied -> fields(number(Tag.Applied.code), number(message.session), number(message.revision))
            is RemoteMessage.Resynchronize -> fields(number(Tag.Resynchronize.code), number(message.session))
            is RemoteMessage.Close -> fields(number(Tag.Close.code), number(message.session), number(message.reason.ordinal))
        }

    private fun projectType(type: ProjectionType): ProjectionValue = fields(ProjectionValue.Text(type.name.namespace), ProjectionValue.Text(type.name.path), number(type.version))

    private fun readType(value: ProjectionValue): ProjectionType =
        read(value) {
            ProjectionType(ResourceId(text(), text()), int(1..Int.MAX_VALUE))
        }

    private fun projectLimits(value: RemoteLimits): ProjectionValue = fields(number(value.frameBytes), number(value.messageBytes), number(value.valueDepth), number(value.collectionEntries), number(value.treeNodes), number(value.pendingBytes), number(value.assemblyMillis), number(value.valueEntries), number(value.reconstructionMillis))

    private fun readLimits(value: ProjectionValue): RemoteLimits =
        read(value) {
            RemoteLimits(int(), int(), int(), int(), int(), int(), long(), int(), long())
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
    }
}
