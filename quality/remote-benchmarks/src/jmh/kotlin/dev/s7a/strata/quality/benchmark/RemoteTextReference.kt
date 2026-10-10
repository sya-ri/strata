package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.remote.RemoteDeclaration
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Independent version-one JDK wire writer for the four complete schemas in the frozen Text corpus.
 * It never invokes either production binary codec; prepared portable projection values remain shared inputs.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object RemoteTextReference {
    /**
     * Produces exact logical wire bytes outside timing from the independently specified message schema.
     */
    public fun encode(message: RemoteMessage): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { write(it, project(message)) }
        return bytes.toByteArray()
    }

    /**
     * Counts Text fields and UTF-8 payload lengths in the reference schema, without claiming runtime work.
     */
    public fun textTotals(message: RemoteMessage): Pair<Int, Int> = textTotals(project(message))

    private fun project(message: RemoteMessage): ProjectionValue =
        when (message) {
            is RemoteMessage.Hello -> {
                fields(number(1), number(message.protocol), limits(message.limits), sequence(message.types.map(::type)))
            }

            is RemoteMessage.Action -> {
                fields(number(4), number(message.session), number(message.sequence), number(message.endpoint), type(message.type), message.value)
            }

            is RemoteMessage.Acknowledgement -> {
                fields(number(5), number(message.session), number(message.sequence), number(message.revision))
            }

            is RemoteMessage.Snapshot -> {
                val settings = message.settings
                require(message.control == null && settings.category == null && settings.visibility.categories.isEmpty() && settings.visibility.screens.isEmpty())
                val input = settings.inputPolicy
                val policy = sequence(listOf(input.movement, input.jump, input.sneak, input.sprint, input.look, input.attack, input.use, input.hotbar, input.drop, input.swapHands, input.pick).map(ProjectionValue::Flag))
                val projectedSettings = fields(number(settings.presentation.ordinal), ProjectionValue.Absent, policy, number(settings.hudOrder), number(settings.visibility.default.ordinal), sequence(emptyList()), sequence(emptyList()))
                fields(
                    number(2),
                    number(message.session),
                    number(message.revision),
                    message.title,
                    fields(
                        number(message.tree.root),
                        sequence(
                            message.tree.nodes.values
                                .map(::node),
                        ),
                    ),
                    ProjectionValue.Flag(message.pausesGame),
                    projectedSettings,
                    ProjectionValue.Absent,
                )
            }

            else -> {
                error("Message is outside the frozen Text reference schemas.")
            }
        }

    private fun limits(value: RemoteLimits): ProjectionValue = fields(number(value.frameBytes), number(value.messageBytes), number(value.valueDepth), number(value.collectionEntries), number(value.treeNodes), number(value.pendingBytes), number(value.assemblyMillis), number(value.valueEntries), number(value.reconstructionMillis), number(value.hudSessions))

    private fun type(value: ProjectionType): ProjectionValue = fields(ProjectionValue.Text(value.name.namespace), ProjectionValue.Text(value.name.path), number(value.version))

    private fun declaration(value: RemoteDeclaration): ProjectionValue = fields(number(value.identity), type(value.type), value.value)

    private fun node(value: RemoteNode): ProjectionValue = fields(declaration(value.declaration), sequence(value.modifiers.map(::declaration)), sequence(value.children.map(::number)))

    private fun textTotals(value: ProjectionValue): Pair<Int, Int> =
        when (value) {
            is ProjectionValue.Text -> {
                1 to value.value.toByteArray(Charsets.UTF_8).size
            }

            is ProjectionValue.Sequence -> {
                value.values.fold(0 to 0) { total, child ->
                    val next = textTotals(child)
                    total.first + next.first to total.second + next.second
                }
            }

            else -> {
                0 to 0
            }
        }

    private fun write(
        output: DataOutputStream,
        value: ProjectionValue,
    ) {
        when (value) {
            ProjectionValue.Absent -> {
                output.writeByte(0)
            }

            is ProjectionValue.Flag -> {
                output.writeByte(1)
                output.writeBoolean(value.value)
            }

            is ProjectionValue.Integer -> {
                output.writeByte(2)
                output.writeLong(value.value)
            }

            is ProjectionValue.Real -> {
                output.writeByte(3)
                output.writeDouble(value.value)
            }

            is ProjectionValue.Text -> {
                output.writeByte(4)
                writeBytes(output, value.value.toByteArray(Charsets.UTF_8))
            }

            is ProjectionValue.Bytes -> {
                output.writeByte(5)
                writeBytes(output, value.toByteArray())
            }

            is ProjectionValue.Sequence -> {
                output.writeByte(6)
                output.writeInt(value.values.size)
                value.values.forEach { write(output, it) }
            }
        }
    }

    private fun writeBytes(
        output: DataOutputStream,
        bytes: ByteArray,
    ) {
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun number(value: Int): ProjectionValue = number(value.toLong())

    private fun number(value: Long): ProjectionValue = ProjectionValue.Integer(value)

    private fun fields(vararg values: ProjectionValue): ProjectionValue = sequence(values.asList())

    private fun sequence(values: List<ProjectionValue>): ProjectionValue = ProjectionValue.Sequence(values)
}
