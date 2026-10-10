package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition

/**
 * One actual focused TextArea host with synthetic immutable glyph assets and no native dependency.
 * Text, viewport, focus, wrap, time and font metrics are fixed before collection; diagnostics remain untimed.
 * Only the current host/state is owned, and every terminal path checks backend/face release.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class PreeditEditor(
    internal val inputs: PreeditCase.Inputs,
    focused: Boolean = true,
    enabled: Boolean = true,
    private val advances: Map<Int, Int> = emptyMap(),
    borrowedState: TextAreaState? = null,
) : AutoCloseable {
    /**
     * Logical complete-frame viewport; padding leaves a 64 by 36 text clip.
     */
    internal val viewport: IntSize = IntSize(72, 44)
    private val stateIdentity = mutableStateOf(borrowedState ?: TextAreaState(inputs.committed, maxLength = inputs.maximum))
    /**
     * Sole authoritative committed value, separate from all uncommitted composition.
     */
    internal val state: TextAreaState get() = stateIdentity.value
    /**
     * Reactive enabled state used by disabled-routing controls.
     */
    internal val enabled = mutableStateOf(enabled)
    /**
     * Actual current backend glyph calls, after the runtime's bounded glyph cache.
     */
    internal var glyphCalls: Int = 0
        private set
    /**
     * Content evaluation count makes cutoff and preedit/state separation observable.
     */
    internal var evaluations: Int = 0
        private set
    /**
     * Injected exact failure for a previously unrequested glyph during actual frame work.
     */
    internal var glyphFailure: Throwable? = null
    /**
     * Exact fallible face cleanup injected only during independent lifetime admission.
     */
    internal var faceCloseFailure: Throwable? = null
    /**
     * Exact fallible backend cleanup injected only during independent lifetime admission.
     */
    internal var backendCloseFailure: Throwable? = null
    private var backends = 0
    private var faces = 0
    private val glyphImage = createDrawImage(IntSize(1, 1), intArrayOf(-1))
    private val snapshot =
        MinecraftFontSnapshot.load(
            listOf(
                MinecraftMemoryFontAssetSource(
                    "preedit-immutable-assets-v1",
                    mapOf(
                        "assets/minecraft/font/default.json" to """{"providers":[{"type":"ttf","file":"preedit:fixed.ttf","size":1}]}""".toByteArray(Charsets.UTF_8),
                        "assets/preedit/font/fixed.ttf" to byteArrayOf(1),
                    ),
                ),
            ),
            MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84),
        )

    /**
     * Real retained session loaded from the archive identified by the shared collector.
     */
    internal val host: MinecraftUiHost =
        createMinecraftUiHost(
            UiDefinition("Frozen TextArea preedit") {
                evaluations++
                state.value
                TextArea(
                    state,
                    TextAreaViewport.Size(viewport),
                    textStyle = TextStyle.ContainerLabel,
                    enabled = this@PreeditEditor.enabled.value,
                    modifier = if (focused) Modifier.Empty.initialFocus() else Modifier.Empty,
                )
            },
            ComponentProfile.create(snapshot),
            fontBackend = MinecraftFontBackendFactory { backend() },
        )

    init {
        check(snapshot.diagnostics.isEmpty())
        try {
            host.attach()
            frame()
        } catch (failure: Throwable) {
            runCatching { host.close() }.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
            check(ownedResources == 0)
            throw failure
        }
    }

    /**
     * Completes input-independent geometry, paint and semantics at the fixed timestamp.
     */
    internal fun frame(): RuntimeUiFrame = host.frame(viewport, FrameTime(0))

    /**
     * Delivers an immutable public event; callers separately complete and validate the frame.
     */
    internal fun input(event: TextInputEvent): InputResult = host.dispatchTextInput(event)

    /**
     * Reconciles a new caller-owned state identity through the next ordinary frame cutoff.
     */
    internal fun replaceState(value: TextAreaState) {
        stateIdentity.value = value
    }

    /**
     * Restores the same bounded active composition before a measured batch, including its real frame work.
     */
    internal fun resetComposition() {
        input(CLEAR)
        frame()
        inputs.seed?.let {
            input(it)
            frame()
        }
    }

    /**
     * Terminal resource count, including renderer backend and face ownership only.
     */
    internal val ownedResources: Int get() = backends + faces

    private fun backend(): MinecraftFontBackend {
        backends++
        return object : MinecraftFontBackend {
            private var closed = false
            override fun decodePng(bytes: ByteArray): DrawImage = error("Preedit fixtures contain no bitmap provider")
            override fun openTrueType(
                bytes: ByteArray,
                settings: MinecraftTrueTypeSettings,
            ): MinecraftTrueTypeFace {
                check(closed.not())
                faces++
                return object : MinecraftTrueTypeFace {
                    private var closed = false
                    override fun glyph(codePoint: Int): MinecraftFontGlyph {
                        check(closed.not())
                        glyphFailure?.let { throw it }
                        glyphCalls++
                        return MinecraftFontGlyph((advances[codePoint] ?: 1).toFloat(), 0f, 0f, 1f, 1f, glyphImage)
                    }
                    override fun close() {
                        if (closed) return
                        closed = true
                        faces--
                        faceCloseFailure?.let { throw it }
                    }
                }
            }
            override fun close() {
                if (closed) return
                closed = true
                backends--
                backendCloseFailure?.let { throw it }
            }
        }
    }

    override fun close() {
        host.close()
        check(ownedResources == 0)
    }

    private companion object {
        val CLEAR = TextInputEvent.Preedit("", 0, emptyList(), -1)
    }
}
