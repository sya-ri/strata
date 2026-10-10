package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.input.TextInputEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Invokes the actual loaded archive's internal normalizer without changing the runtime API.
 * Lookup is prepared outside timing; invocation overhead is identical in both variants and remains measured.
 */
internal class PreeditRuntime {
    private val type = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextAreaComposition")
    private val instance = type.getField("INSTANCE").get(null)
    private val normalize =
        type.declaredMethods.single {
            it.name.startsWith("normalize") && it.parameterTypes.toList() == listOf(TextInputEvent.Preedit::class.java, Int::class.javaPrimitiveType)
        }
    private val resultType = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextAreaPreedit")
    private val text = getter("getFullText")
    private val caret = getter("getCaretPosition")
    private val focused = getter("getFocusedRange")

    /**
     * Runs complete real normalization; an exception retains its original cause and timing boundary.
     */
    internal fun normalize(
        event: TextInputEvent.Preedit,
        remaining: Int,
    ): Any? =
        try {
            normalize.invoke(instance, event, remaining)
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }

    /**
     * Extracts immutable output for untimed independent comparisons.
     */
    internal fun output(value: Any): Triple<String, Int, IntRange?> =
        Triple(text.invoke(value) as String, caret.invoke(value) as Int, focused.invoke(value) as IntRange?)

    private fun getter(prefix: String): Method = resultType.declaredMethods.single { it.name.startsWith(prefix) && it.parameterCount == 0 }
}
