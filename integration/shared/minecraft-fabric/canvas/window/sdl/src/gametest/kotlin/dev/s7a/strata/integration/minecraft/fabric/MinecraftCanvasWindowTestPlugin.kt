package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.WindowEventHandler
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.loader.api.FabricLoader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin
import org.spongepowered.asm.mixin.extensibility.IMixinInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged
import org.spongepowered.asm.util.Annotations

/**
 * Verifies Fabric's exact loaded-test cancellation body before allowing scoped native focus acceptance.
 * Checks the originating mixin, unique signature and every executable instruction.
 * The focus id comes from the generated invoker, including production remapping.
 * Unexpected upstream shapes abort transformation; no published runtime includes this plugin.
 */
@InternalStrataRuntimeApi
public class MinecraftCanvasWindowTestPlugin : IMixinConfigPlugin {
    override fun onLoad(mixinPackage: String): Unit = Unit

    override fun getRefMapperConfig(): String? = null

    override fun shouldApplyMixin(
        targetClassName: String,
        mixinClassName: String,
    ): Boolean = true

    override fun acceptTargets(
        myTargets: Set<String>,
        otherTargets: Set<String>,
    ): Unit = Unit

    override fun getMixins(): List<String>? = null

    override fun preApply(
        targetClassName: String,
        targetClass: ClassNode,
        mixinClassName: String,
        mixinInfo: IMixinInfo,
    ): Unit = Unit

    override fun postApply(
        targetClassName: String,
        targetClass: ClassNode,
        mixinClassName: String,
        mixinInfo: IMixinInfo,
    ) {
        if (mixinClassName != WINDOW_ACCESS || FabricLoader.getInstance().isModLoaded("fabric-client-gametest-api-v1").not() || System.getProperty("fabric.client.gametest") == null) return
        restoreSurfaceNotification(targetClass)
        val cancellation = findCancellation(targetClass)
        verifyCancellation(cancellation)
        val focusCallback = findNativeFocus(targetClass)
        val guarded = InsnList()
        guarded.add(VarInsnNode(Opcodes.ALOAD, 0))
        guarded.add(VarInsnNode(Opcodes.ALOAD, 1))
        guarded.add(LdcInsnNode(focusCallback))
        guarded.add(MethodInsnNode(Opcodes.INVOKESTATIC, SCOPE, "cancelUnlessNativeFocus", Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(Any::class.java), Type.getType(CallbackInfo::class.java), Type.getType(String::class.java)), false))
        guarded.add(InsnNode(Opcodes.RETURN))
        cancellation.instructions.clear()
        cancellation.instructions.add(guarded)
        cancellation.localVariables = null
        cancellation.maxStack = 3
    }

    /**
     * Restores the native surface invalidation omitted by Fabric's verified synthetic physical-resize path.
     */
    private fun restoreSurfaceNotification(targetClass: ClassNode) {
        val calls =
            targetClass.methods.filter { it.name.contentEquals("fabric_resize") && it.desc.contentEquals("(II)V") }.flatMap { method ->
                val merged = Annotations.getVisible(method, MixinMerged::class.java)
                if (merged == null || Annotations.getValue<String>(merged, "mixin") != FABRIC_WINDOW_MIXIN) {
                    emptyList()
                } else {
                    method.instructions.filterIsInstance<MethodInsnNode>().filter { call ->
                        call.owner == Type.getInternalName(WindowEventHandler::class.java) && call.name.contentEquals("resizeGui") && call.desc.contentEquals("()V")
                    }
                }
            }
        check(calls.size == 1) { "Expected Fabric's single native GUI resize notification." }
        calls.single().name = "framebufferSizeChanged"
    }

    private fun findCancellation(targetClass: ClassNode): MethodNode {
        val matches =
            targetClass.methods.filter { method ->
                val merged = Annotations.getVisible(method, MixinMerged::class.java)
                merged != null && Annotations.getValue<String>(merged, "mixin") == FABRIC_WINDOW_MIXIN && method.desc == CANCELLATION_DESCRIPTOR
            }
        check(matches.size == 1) { "Expected exactly one verified Fabric window cancellation handler, found ${matches.size}." }
        return matches.single()
    }

    private fun verifyCancellation(method: MethodNode) {
        val code = method.instructions.filter { 0 <= it.opcode }
        check(
            method.access and Opcodes.ACC_PRIVATE != 0 && method.access and Opcodes.ACC_STATIC == 0 && method.tryCatchBlocks.isEmpty() && code.size == 3 &&
                code[0] is VarInsnNode && code[0].opcode == Opcodes.ALOAD && (code[0] as VarInsnNode).`var` == 1 &&
                code[1] is MethodInsnNode && code[1].opcode == Opcodes.INVOKEVIRTUAL && code[2].opcode == Opcodes.RETURN,
        ) {
            "Fabric's window cancellation no longer consists only of CallbackInfo.cancel() and return."
        }
        val cancel = code[1] as MethodInsnNode
        check(cancel.owner == CALLBACK && cancel.name.contentEquals("cancel") && cancel.desc.contentEquals("()V") && cancel.itf.not()) {
            "Fabric's window cancellation no longer invokes the verified CallbackInfo.cancel() method."
        }
    }

    private fun findNativeFocus(targetClass: ClassNode): String {
        val calls =
            targetClass.methods.filter { it.name.contentEquals("strataCanvasFocus") && it.desc.contentEquals("(Z)V") }.flatMap { method ->
                method.instructions.filterIsInstance<MethodInsnNode>().filter { it.owner == targetClass.name && it.desc.contentEquals("(Z)V") }
            }
        check(calls.size == 1) { "The test invoker must identify exactly one native window focus callback." }
        return calls.single().name
    }

    private companion object {
        const val WINDOW_ACCESS = "dev.s7a.strata.integration.minecraft.fabric.mixin.canvas.MinecraftCanvasWindowTestAccess"
        const val FABRIC_WINDOW_MIXIN = "net.fabricmc.fabric.mixin.client.gametest.input.WindowMixin"
        const val SCOPE = "dev/s7a/strata/integration/minecraft/fabric/MinecraftCanvasWindowTestScope"
        val CALLBACK: String = Type.getInternalName(CallbackInfo::class.java)
        val CANCELLATION_DESCRIPTOR: String = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(CallbackInfo::class.java))
    }
}
