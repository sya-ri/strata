package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.text.TranslationFallback
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.UiTextArgument

/**
 * Portable unresolved text preserving translation arguments and resource-pack font selection.
 * Opaque platform payloads require an explicit application extension and are rejected here.
 */
public object RemoteTextCodec {
    /**
     * Encodes a portable text value with a bounded recursive structure.
     */
    public fun encode(text: UiText): ProjectionValue = encode(text, 0)

    /**
     * Decodes a complete portable text value, rejecting malformed or unsupported variants.
     */
    public fun decode(value: ProjectionValue): UiText = decode(value, 0)

    private fun encode(
        text: UiText,
        depth: Int,
    ): ProjectionValue {
        require(depth < 64) { "Text nesting exceeds the protocol limit." }
        return when (text) {
            is UiText.Literal -> {
                fields(TextKind.Literal, ProjectionValue.Text(text.value))
            }

            is UiText.WithFont -> {
                fields(TextKind.Font, ProjectionValue.Text(text.font.namespace), ProjectionValue.Text(text.font.path), encode(text.text, depth + 1))
            }

            is UiText.Concatenated -> {
                fields(TextKind.Concatenation, ProjectionValue.Sequence(text.parts.map { encode(it, depth + 1) }))
            }

            is UiText.Translated -> {
                fields(
                    TextKind.Translation,
                    ProjectionValue.Text(text.key),
                    when (val fallback = text.fallback) {
                        TranslationFallback.UseKey -> ProjectionValue.Absent
                        is TranslationFallback.Literal -> ProjectionValue.Text(fallback.value)
                    },
                    ProjectionValue.Sequence(text.arguments.map { argument(it, depth + 1) }),
                )
            }

            is UiText.Platform -> {
                throw RemoteProtocolException(RemoteFailure.UnsupportedType, "Opaque platform text needs an installed remote text extension.")
            }
        }
    }

    private fun decode(
        value: ProjectionValue,
        depth: Int,
    ): UiText {
        require(depth < 64) { "Text nesting exceeds the protocol limit." }
        val fields = ProjectionFields(value)
        val result =
            when (TextKind.entries[fields.int(TextKind.entries.indices)]) {
                TextKind.Literal -> {
                    UiText.Literal(fields.text())
                }

                TextKind.Font -> {
                    val font = ResourceId(fields.text(), fields.text())
                    UiText.WithFont(decode(fields.value(), depth + 1), font)
                }

                TextKind.Concatenation -> {
                    UiText.Concatenated(fields.values().map { decode(it, depth + 1) })
                }

                TextKind.Translation -> {
                    val key = fields.text()
                    val fallback =
                        when (val encoded = fields.value()) {
                            ProjectionValue.Absent -> TranslationFallback.UseKey
                            is ProjectionValue.Text -> TranslationFallback.Literal(encoded.value)
                            else -> error("Invalid translation fallback.")
                        }
                    UiText.Translated(key, fields.values().map { argument(it, depth + 1) }, fallback)
                }
            }
        fields.finish()
        return result
    }

    private fun argument(
        value: UiTextArgument,
        depth: Int,
    ): ProjectionValue =
        when (value) {
            is UiTextArgument.Text -> fields(ArgumentKind.Text, encode(value.value, depth))
            is UiTextArgument.StringValue -> fields(ArgumentKind.String, ProjectionValue.Text(value.value))
            is UiTextArgument.IntValue -> fields(ArgumentKind.Int, ProjectionValue.Integer(value.value.toLong()))
            is UiTextArgument.LongValue -> fields(ArgumentKind.Long, ProjectionValue.Integer(value.value))
            is UiTextArgument.FloatValue -> fields(ArgumentKind.Float, ProjectionValue.Real(value.value.toDouble()))
            is UiTextArgument.DoubleValue -> fields(ArgumentKind.Double, ProjectionValue.Real(value.value))
            is UiTextArgument.BooleanValue -> fields(ArgumentKind.Boolean, ProjectionValue.Flag(value.value))
        }

    private fun argument(
        value: ProjectionValue,
        depth: Int,
    ): UiTextArgument {
        val fields = ProjectionFields(value)
        val result =
            when (ArgumentKind.entries[fields.int(ArgumentKind.entries.indices)]) {
                ArgumentKind.Text -> UiTextArgument.Text(decode(fields.value(), depth))
                ArgumentKind.String -> UiTextArgument.StringValue(fields.text())
                ArgumentKind.Int -> UiTextArgument.IntValue(fields.int())
                ArgumentKind.Long -> UiTextArgument.LongValue(fields.long())
                ArgumentKind.Float -> UiTextArgument.FloatValue(fields.real().toFloat().also { require(it.isFinite()) })
                ArgumentKind.Double -> UiTextArgument.DoubleValue(fields.real())
                ArgumentKind.Boolean -> UiTextArgument.BooleanValue(fields.flag())
            }
        fields.finish()
        return result
    }

    private fun fields(
        kind: Enum<*>,
        vararg values: ProjectionValue,
    ): ProjectionValue = ProjectionValue.Sequence(listOf(ProjectionValue.Integer(kind.ordinal.toLong())) + values)

    private enum class TextKind { Literal, Font, Concatenation, Translation }

    private enum class ArgumentKind { Text, String, Int, Long, Float, Double, Boolean }
}
