package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget

/**
 * Supplies the synchronous destruction acknowledgement for OpenGL-only Canvas target families.
 *
 * The render-thread caller retains ownership of [target] and invokes the probe only after its close succeeds.
 * These families release owned framebuffers, textures, and views during close after the manager's GPU fences complete.
 * The probe retains no native resource and performs no work or allocation after construction.
 * Construction and polling have no native failure path; the caller must not use the probe to bypass a failed close.
 *
 * @param target native target whose ownership remains with the caller and is not retained by the probe.
 * @return an immutable acknowledgment that is valid only after successful synchronous native release.
 */
@Suppress("UNUSED_PARAMETER")
@JvmSynthetic
internal fun trackCanvasDestruction(target: RenderTarget): FabricNativeCanvasDestruction = FabricNativeCanvasDestruction { true }

/**
 * Supplies synchronous physical acknowledgement for already-owned portable textures and views in OpenGL-only families.
 *
 * The render-thread caller must invoke the returned probe only after all native close requests succeed behind the GUI-generation fences.
 * The resource list remains owned by the caller; this probe retains no native object and cannot release storage itself.
 * Construction and polling allocate no native resource and have no native failure path.
 */
@Suppress("UNUSED_PARAMETER")
@JvmSynthetic
internal fun trackPortableDestruction(resources: List<AutoCloseable>): FabricNativeCanvasDestruction = FabricNativeCanvasDestruction { true }
