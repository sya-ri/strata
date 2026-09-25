package dev.s7a.strata.integration.minecraft.fabric;

import dev.s7a.strata.spi.InternalStrataRuntimeApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;
import org.spongepowered.asm.util.Annotations;

/**
 * Guards the exact upstream client-test cancellation body so an explicit test can reach native focus handling.
 *
 * <p>This plugin runs only in the loaded acceptance source set and edits no production or vanilla callback body.
 * It verifies the originating Fabric mixin, the unique callback signature, and every executable instruction.
 * The native focus id comes from Strata's generated invoker, including production remapping.
 * Unexpected upstream or generated shapes abort class transformation instead of weakening input cancellation.</p>
 */
@InternalStrataRuntimeApi
public final class MinecraftCanvasWindowTestPlugin implements IMixinConfigPlugin {
    private static final String WINDOW_ACCESS = "dev.s7a.strata.integration.minecraft.fabric.mixin.canvas.MinecraftCanvasWindowTestAccess";
    private static final String FABRIC_WINDOW_MIXIN = "net.fabricmc.fabric.mixin.client.gametest.input.WindowMixin";
    private static final String SCOPE = "dev/s7a/strata/integration/minecraft/fabric/MinecraftCanvasWindowTestScope";
    private static final String CALLBACK = Type.getInternalName(CallbackInfo.class);
    private static final String CANCELLATION_DESCRIPTOR = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(CallbackInfo.class));

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (WINDOW_ACCESS.equals(mixinClassName) == false
            || FabricLoader.getInstance().isModLoaded("fabric-client-gametest-api-v1") == false
            || System.getProperty("fabric.client.gametest") == null) {
            return;
        }
        MethodNode cancellation = findCancellation(targetClass);
        verifyCancellation(cancellation);
        String focusCallback = findNativeFocus(targetClass);
        InsnList guarded = new InsnList();
        guarded.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guarded.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guarded.add(new LdcInsnNode(focusCallback));
        guarded.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            SCOPE,
            "cancelUnlessNativeFocus",
            Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(Object.class), Type.getType(CallbackInfo.class), Type.getType(String.class)),
            false
        ));
        guarded.add(new InsnNode(Opcodes.RETURN));
        cancellation.instructions.clear();
        cancellation.instructions.add(guarded);
        cancellation.localVariables = null;
        cancellation.maxStack = 3;
    }

    private static MethodNode findCancellation(ClassNode targetClass) {
        List<MethodNode> matches = new ArrayList<>();
        for (MethodNode method : targetClass.methods) {
            AnnotationNode merged = Annotations.getVisible(method, MixinMerged.class);
            if (merged != null && FABRIC_WINDOW_MIXIN.equals(Annotations.<String>getValue(merged, "mixin"))
                && CANCELLATION_DESCRIPTOR.equals(method.desc)) {
                matches.add(method);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected exactly one verified Fabric window cancellation handler, found " + matches.size() + ".");
        }
        return matches.get(0);
    }

    private static void verifyCancellation(MethodNode method) {
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (0 <= instruction.getOpcode()) {
                code.add(instruction);
            }
        }
        if ((method.access & Opcodes.ACC_PRIVATE) == 0 || (method.access & Opcodes.ACC_STATIC) != 0
            || method.tryCatchBlocks.isEmpty() == false || code.size() != 3
            || (code.get(0) instanceof VarInsnNode) == false || code.get(0).getOpcode() != Opcodes.ALOAD
            || ((VarInsnNode) code.get(0)).var != 1 || (code.get(1) instanceof MethodInsnNode) == false
            || code.get(1).getOpcode() != Opcodes.INVOKEVIRTUAL || code.get(2).getOpcode() != Opcodes.RETURN) {
            throw new IllegalStateException("Fabric's window cancellation no longer consists only of CallbackInfo.cancel() and return.");
        }
        MethodInsnNode cancel = (MethodInsnNode) code.get(1);
        if (CALLBACK.equals(cancel.owner) == false || "cancel".equals(cancel.name) == false || "()V".equals(cancel.desc) == false || cancel.itf) {
            throw new IllegalStateException("Fabric's window cancellation no longer invokes the verified CallbackInfo.cancel() method.");
        }
    }

    private static String findNativeFocus(ClassNode targetClass) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : targetClass.methods) {
            if ("strataCanvasFocus".equals(method.name) && "(JZ)V".equals(method.desc)) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && targetClass.name.equals(call.owner) && "(JZ)V".equals(call.desc)) {
                        calls.add(call);
                    }
                }
            }
        }
        if (calls.size() != 1) {
            throw new IllegalStateException("The test invoker must identify exactly one native window focus callback.");
        }
        return calls.get(0).name;
    }
}
