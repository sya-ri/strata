package dev.s7a.strata.text

import dev.s7a.strata.resource.ResourceId
import java.util.Collections

/**
 * Unresolved text retained by the platform-neutral tree.
 *
 * Runtime adapters resolve values only at their platform boundary.
 * Version 0.1.1 adds [WithFont] to this sealed hierarchy; exhaustive visitors written for earlier variants need an additional branch.
 * Retaining the older JVM members does not make previously compiled visitors accept the new variant.
 */
public sealed interface UiText {
    /**
     * A text value that needs no lookup.
     *
     * @property value the literal text.
     */
    public data class Literal(
        public val value: String,
    ) : UiText

    /**
     * Text carrying a resource-pack font identifier without resolving or owning the font resource.
     *
     * The immutable wrapper is safe to retain and share across threads under the wrapped text's ownership contract.
     * Runtime adapters resolve [font] against their pinned resource state when measuring and drawing [text].
     * The font is inherited by the wrapped text; a nested [WithFont] takes precedence over this outer selection.
     *
     * @property text unresolved text to render with the selected font.
     * @property font structural resource identifier of the font definition.
     */
    public data class WithFont(
        public val text: UiText,
        public val font: ResourceId,
    ) : UiText

    /**
     * A translation key with typed arguments and a non-null fallback policy.
     *
     * The argument list is defensively snapshotted.
     * The [TranslationFallback.UseKey] fallback preserves and displays the key when lookup is unavailable.
     *
     * @property key the non-blank translation key.
     * @property arguments immutable typed translation arguments.
     * @property fallback the typed fallback policy.
     */
    public class Translated public constructor(
        public val key: String,
        arguments: List<UiTextArgument> = emptyList(),
        public val fallback: TranslationFallback = TranslationFallback.UseKey,
    ) : UiText {
        public val arguments: List<UiTextArgument> = Collections.unmodifiableList(arguments.toList())

        init {
            require(key.isNotBlank()) { "Translation keys must not be blank." }
        }

        /**
         * Creates a translation with a literal fallback.
         *
         * @param key the non-blank translation key.
         * @param fallback the literal fallback text.
         */
        public constructor(key: String, fallback: String) : this(key, emptyList(), TranslationFallback.Literal(fallback))

        /**
         * Creates a translation with typed arguments and a literal fallback.
         *
         * @param key the non-blank translation key.
         * @param arguments the typed translation arguments.
         * @param fallback the literal fallback text.
         */
        public constructor(key: String, arguments: List<UiTextArgument>, fallback: String) :
            this(key, arguments, TranslationFallback.Literal(fallback))

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other is Translated) {
                return key == other.key && arguments == other.arguments && fallback == other.fallback
            }
            return false
        }

        override fun hashCode(): Int = 31 * (31 * key.hashCode() + arguments.hashCode()) + fallback.hashCode()

        override fun toString(): String = "Translated(key=$key, arguments=$arguments, fallback=$fallback)"
    }

    /**
     * A concatenation whose parts remain individually unresolved.
     *
     * @property parts immutable text snapshots in declaration order.
     */
    public class Concatenated public constructor(
        parts: List<UiText>,
    ) : UiText {
        public val parts: List<UiText> = Collections.unmodifiableList(parts.toList())

        init {
            require(parts.isNotEmpty()) { "Concatenated text must contain at least one part." }
        }

        /**
         * Creates a concatenation from vararg parts.
         *
         * @param parts the unresolved text values.
         */
        public constructor(vararg parts: UiText) : this(parts.toList())

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other is Concatenated) {
                return parts == other.parts
            }
            return false
        }

        override fun hashCode(): Int = parts.hashCode()

        override fun toString(): String = "Concatenated(parts=$parts)"
    }

    /**
     * A typed platform payload retained without interpreting its ownership or namespace.
     *
     * @property payload the opaque platform text value.
     */
    public data class Platform(
        public val payload: PlatformText,
    ) : UiText

    /**
     * Factory methods for retaining text composition.
     */
    public companion object {
        /**
         * Concatenates text values while preserving their unresolved representation.
         *
         * @param parts the values to concatenate.
         * @return a single value retaining each part in order.
         */
        public fun concat(vararg parts: UiText): UiText =
            when (parts.size) {
                0 -> Literal("")
                1 -> parts[0]
                else -> Concatenated(parts.toList())
            }
    }
}
