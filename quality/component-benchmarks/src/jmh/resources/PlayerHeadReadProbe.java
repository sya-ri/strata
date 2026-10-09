import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * Untimed source-launch probe using the runner's standard Class-File API.
 * Only immutable image reads and successful image construction receive counter calls.
 * Timed archives, interpolation instructions and public image ownership are never rewritten on disk.
 */
public final class PlayerHeadReadProbe {
    private static final String IMAGE = "dev.s7a.strata.render.DrawImageSnapshot";
    private static final String FIXTURE = "dev.s7a.strata.quality.benchmark.PlayerHeadLayerBenchmark";
    private static boolean active;
    private static int area;
    private static long reads;
    private static long layers;

    private PlayerHeadReadProbe() {
    }

    /** Starts one operation and admits only completed layer arrays of its requested area. */
    public static void begin(int requestedArea) {
        active = true;
        area = requestedArea;
        reads = 0;
        layers = 0;
    }

    /** Records an actual immutable source read only within the owner operation. */
    public static void read() {
        if (active) reads++;
    }

    /** Records successful construction; source/profile assets were created before admission. */
    public static void constructed(int[] pixels) {
        if (active && pixels.length == area) layers++;
    }

    /** Runs full untimed admission with original/transformed image byte provenance. */
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No probe arguments.");
        URL[] urls = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .map(value -> {
                try {
                    return Path.of(value).toUri().toURL();
                } catch (IOException failure) {
                    throw new IllegalArgumentException(failure);
                }
            }).toArray(URL[]::new);
        try (ProbeLoader loader = new ProbeLoader(urls)) {
            var fixture = loader.loadClass(FIXTURE);
            var method = fixture.getMethod("verifyReadWork", IntConsumer.class, LongSupplier.class, LongSupplier.class, Runnable.class);
            try {
                method.invoke(null, (IntConsumer) PlayerHeadReadProbe::begin,
                    (LongSupplier) () -> reads, (LongSupplier) () -> layers, (Runnable) () -> active = false);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Error error) throw error;
                if (cause instanceof Exception exception) throw exception;
                throw failure;
            }
            if (loader.transformed != 1) throw new IllegalStateException("The actual immutable-image class was not instrumented exactly once.");
        }
    }

    /** Child-first Strata loading preserves one runtime family and resolves counter callbacks through its parent. */
    private static final class ProbeLoader extends URLClassLoader {
        private int transformed;

        private ProbeLoader(URL[] urls) {
            super(urls, PlayerHeadReadProbe.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = name.startsWith("dev.s7a.strata.") ? findClass(name) : super.loadClass(name, false);
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (IMAGE.equals(name) == false) return super.findClass(name);
            URL resource = Objects.requireNonNull(findResource(name.replace('.', '/') + ".class"));
            try (var stream = resource.openStream()) {
                byte[] original = stream.readAllBytes();
                ClassFile classFile = ClassFile.of();
                ClassModel model = classFile.parse(original);
                long readMethods = model.methods().stream().filter(method -> method.methodName().equalsString("argbAt") && method.methodType().equalsString("(II)I")).count();
                long constructors = model.methods().stream().filter(method -> method.methodName().equalsString("<init>") && method.methodType().equalsString("(Ldev/s7a/strata/geometry/IntSize;[I)V")).count();
                if (readMethods != 1 || constructors != 1) throw new IllegalStateException("Review the changed actual image bytecode before instrumenting.");
                ClassDesc counter = ClassDesc.of("PlayerHeadReadProbe");
                ClassTransform readTransform = ClassTransform.transformingMethodBodies(
                    method -> method.methodName().equalsString("argbAt"),
                    new CodeTransform() {
                        @Override
                        public void atStart(CodeBuilder builder) {
                            builder.invokestatic(counter, "read", MethodTypeDesc.of(ConstantDescs.CD_void));
                        }

                        @Override
                        public void accept(CodeBuilder builder, CodeElement element) {
                            builder.with(element);
                        }
                    });
                ClassTransform constructorTransform = ClassTransform.transformingMethodBodies(
                    method -> method.methodName().equalsString("<init>"),
                    (builder, element) -> {
                        if (element instanceof ReturnInstruction instruction && instruction.opcode() == Opcode.RETURN) {
                            builder.aload(2).invokestatic(counter, "constructed", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int.arrayType()));
                        }
                        builder.with(element);
                    });
                // JDK 25 intersects chained method filters, so apply these disjoint filters in separate passes.
                byte[] readInstrumented = classFile.transformClass(model, readTransform);
                byte[] instrumented = classFile.transformClass(classFile.parse(readInstrumented), constructorTransform);
                if (Arrays.equals(original, readInstrumented) || Arrays.equals(readInstrumented, instrumented)) {
                    throw new IllegalStateException("Both image read and construction instrumentation must change the actual bytecode.");
                }
                transformed++;
                System.out.println("playerHeadReadProbe,imageOrigin=" + resource + ",originalSha256=" + digest(original) + ",instrumentedSha256=" + digest(instrumented));
                return defineClass(name, instrumented, 0, instrumented.length);
            } catch (IOException failure) {
                throw new ClassNotFoundException(name, failure);
            }
        }

        private static String digest(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
