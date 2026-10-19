package dev.s7a.strata.runtime.minecraft

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodHandles.Lookup.ClassOption
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Counts real integer-array loads in a test-only hidden copy of the compiled lookup implementation.
 * The original runtime remains unmodified; construction and admission are outside each observation.
 */
internal class MinecraftTextLineReadProbe(
    line: MinecraftTextLine,
) {
    private val instance: Any
    private val counter: Field
    private val lookupMethod: Method

    init {
        val original = MinecraftTextLine::class.java
        val resource = "/${original.name.replace('.', '/')}.class"
        val bytes = checkNotNull(original.getResourceAsStream(resource)).use { stream -> stream.readBytes() }
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        val fieldName = "test${original.simpleName}ArrayReads"
        val methods = setOf("offsetAt", "nearestIndex", "lowerBound", "scannedIndex")
        var sites = 0
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    val visitor = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
                    if (name?.substringBefore('$') in methods) {
                        return object : MethodVisitor(Opcodes.ASM9, visitor) {
                            override fun visitInsn(opcode: Int) {
                                if (opcode == Opcodes.IALOAD) {
                                    sites++
                                    super.visitFieldInsn(Opcodes.GETSTATIC, reader.className, fieldName, "I")
                                    super.visitInsn(Opcodes.ICONST_1)
                                    super.visitInsn(Opcodes.IADD)
                                    super.visitFieldInsn(Opcodes.PUTSTATIC, reader.className, fieldName, "I")
                                }
                                super.visitInsn(opcode)
                            }
                        }
                    }
                    return visitor
                }

                override fun visitEnd() {
                    super.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, fieldName, "I", null, null).visitEnd()
                    super.visitEnd()
                }
            },
            0,
        )
        check(0 < sites) { "The compiled lookup contains no instrumented integer-array loads." }
        val type = MethodHandles.privateLookupIn(original, MethodHandles.lookup()).defineHiddenClass(writer.toByteArray(), true, ClassOption.NESTMATE).lookupClass()
        val constructor = type.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, original.getDeclaredField("run").type, IntArray::class.java, IntArray::class.java)
        check(constructor.trySetAccessible())
        instance = constructor.newInstance(line.start, line.end, line.nextStart, line.run, array(line, "offsets"), array(line, "positions"))
        counter = type.getDeclaredField(fieldName).apply { check(trySetAccessible()) }
        lookupMethod =
            type.declaredMethods
                .associateBy { method -> method.name.substringBefore('$') }
                .getValue(MinecraftTextLine::offsetAt.name)
                .apply { check(trySetAccessible()) }
    }

    /**
     * Returns the real selected offset and observed array loads, including its final offset-array read.
     */
    internal fun lookup(x: Int): Sample {
        counter.setInt(null, 0)
        val offset = lookupMethod.invoke(instance, x) as Int
        return Sample(offset, counter.getInt(null))
    }

    private fun array(
        line: MinecraftTextLine,
        name: String,
    ): IntArray =
        MinecraftTextLine::class.java
            .getDeclaredField(name)
            .apply { check(trySetAccessible()) }
            .get(line) as IntArray

    /**
     * Observed lookup result and work from one instrumented invocation.
     */
    internal data class Sample(
        val offset: Int,
        val arrayReads: Int,
    )
}
