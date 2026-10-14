package dev.s7a.strata.quality.benchmark;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/** Provides one prebound primitive lookup handle for an actual retained line. */
final class TextAreaLookupAccess {
    private TextAreaLookupAccess() {}

    /** Resolves the existing synthetic internal method outside sampling, without boxing its timed argument or result. */
    static IntUnaryOperator create(Object line) throws IllegalAccessException {
        Method method = Arrays.stream(line.getClass().getDeclaredMethods())
            .filter(candidate -> candidate.getName().split("\\$")[0].equals("offsetAt"))
            .filter(candidate -> Arrays.equals(candidate.getParameterTypes(), new Class<?>[] {int.class}))
            .findFirst().orElseThrow();
        method.setAccessible(true);
        MethodHandle handle = MethodHandles.lookup().unreflect(method).bindTo(line);
        return x -> {
            try {
                return (int) handle.invokeExact(x);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Logical insertion lookup failed", failure);
            }
        };
    }
}
