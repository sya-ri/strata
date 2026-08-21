@file:JvmName("MinecraftRuntimeFactories")

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Creates a one-shot Minecraft screen definition.
 *
 * Construction does not evaluate [content].
 * The returned definition owns its arguments until one host atomically transfers them or [MinecraftScreenDefinition.close] releases them.
 *
 * @param title the unresolved screen title.
 * @param pausesGame whether the screen pauses the game.
 * @param content the owner-thread element evaluator invoked with an implicit Minecraft component receiver during the transferred host's first attach.
 * @return a one-shot definition with referential identity.
 */
public fun createMinecraftScreenDefinition(
    title: UiText,
    pausesGame: Boolean = false,
    content: MinecraftUiContext.() -> Element,
): MinecraftScreenDefinition = MinecraftDefinitionImplementation.create(title, pausesGame, content)

/**
 * Creates owner-thread state for one Minecraft TextField.
 *
 * @param initialValue initial printable-ASCII value.
 * @param maxLength positive maximum UTF-16 length; Minecraft EditBox defaults to 32 and individual screens may select a larger bound.
 * @return mutable state detached from any UI tree.
 * @throws IllegalArgumentException when [maxLength] is not positive or [initialValue] is unsupported or too long.
 */
public fun createMinecraftTextFieldState(
    initialValue: String = "",
    maxLength: Int = 32,
): MinecraftTextFieldState = MinecraftProfileImplementation.createTextFieldState(initialValue, maxLength)

/**
 * Creates one owner-thread host by atomically consuming a definition.
 *
 * Successful construction leaves the definition empty and transfers metadata and content to the new host.
 *
 * @param definition the available one-shot definition.
 * @param profile the complete immutable Minecraft asset profile.
 * @return a distinct owner-thread host.
 * @throws IllegalStateException when [definition] was already transferred or closed.
 */
@InternalStrataRuntimeApi
public fun createMinecraftUiHost(
    definition: MinecraftScreenDefinition,
    profile: MinecraftUiProfile,
): MinecraftUiHost = MinecraftHostImplementation.create(definition, profile)

/**
 * Creates one complete immutable Minecraft UI profile.
 *
 * The callback and builder are confined to the calling thread and the callback's dynamic lifetime.
 * The builder closes in `finally`, so escaped use fails after both success and failure.
 *
 * @param content the profile declaration callback.
 * @return a complete immutable profile with referential identity.
 * @throws IllegalArgumentException when a declared slot is invalid, duplicated, or missing.
 * @throws Throwable when [content] fails; the exact callback failure is propagated unchanged.
 */
@InternalStrataRuntimeApi
public fun createMinecraftUiProfile(content: MinecraftUiProfileBuilder.() -> Unit): MinecraftUiProfile = MinecraftProfileImplementation.create(content)
