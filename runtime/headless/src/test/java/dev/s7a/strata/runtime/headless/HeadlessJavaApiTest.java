package dev.s7a.strata.runtime.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.s7a.strata.element.Element;
import dev.s7a.strata.geometry.IntSize;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies Java callers can invoke both generated two- and three-argument facade overloads.
 */
final class HeadlessJavaApiTest {
    @Test
    void javaCanCallAllFacadeOverloads() {
        HeadlessImage image = HeadlessRendering.rasterizeHeadless(List.of(), new IntSize(1, 1));
        HeadlessImage scaled = HeadlessRendering.rasterizeHeadless(List.of(), new IntSize(1, 1), 2);

        assertEquals(new IntSize(1, 1), image.getSize());
        assertEquals(new IntSize(2, 2), scaled.getSize());
        assertThrows(
            NullPointerException.class,
            () -> HeadlessRendering.renderHeadless(null, new IntSize(0, 1))
        );
        assertThrows(
            NullPointerException.class,
            () -> HeadlessRendering.renderHeadless(null, new IntSize(0, 1), 1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> HeadlessRendering.rasterizeHeadless(Collections.singletonList(null), new IntSize(1, 1))
        );
    }

    @Test
    void publicResultAndFacadeSurfaceIsExact() {
        assertSealedReadOnlyInterface(HeadlessImage.class, Set.of("getSize", "argbAt", "copyArgb", "encodePng"));
        assertSealedReadOnlyInterface(HeadlessFrame.class, Set.of("getViewport", "getPixelScale", "getImage", "getSemantics"));
        assertMethod(HeadlessImage.class, "getSize", IntSize.class);
        assertMethod(HeadlessImage.class, "argbAt", int.class, int.class, int.class);
        assertMethod(HeadlessImage.class, "copyArgb", int[].class);
        assertMethod(HeadlessImage.class, "encodePng", byte[].class);
        assertMethod(HeadlessFrame.class, "getViewport", IntSize.class);
        assertMethod(HeadlessFrame.class, "getPixelScale", int.class);
        assertMethod(HeadlessFrame.class, "getImage", HeadlessImage.class);
        assertMethod(HeadlessFrame.class, "getSemantics", List.class);

        int publicMethodCount = 0;
        for (Method method : HeadlessRendering.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                if (method.isSynthetic()) {
                    continue;
                }
                publicMethodCount += 1;
                assertTrue(Modifier.isStatic(method.getModifiers()));
            }
        }
        assertEquals(4, publicMethodCount);
        assertFacadeMethod("rasterizeHeadless", HeadlessImage.class, List.class, IntSize.class);
        assertFacadeMethod("rasterizeHeadless", HeadlessImage.class, List.class, IntSize.class, int.class);
        assertFacadeMethod("renderHeadless", HeadlessFrame.class, Element.class, IntSize.class);
        assertFacadeMethod("renderHeadless", HeadlessFrame.class, Element.class, IntSize.class, int.class);
    }

    private static void assertSealedReadOnlyInterface(Class<?> type, Set<String> methodNames) {
        assertTrue(type.isSealed());
        assertTrue(type.isInterface());
        assertEquals(0, type.getDeclaredConstructors().length);
        assertEquals(0, type.getDeclaredFields().length);
        assertEquals(0, type.getDeclaredClasses().length);
        Class<?>[] permitted = type.getPermittedSubclasses();
        assertTrue(0 < permitted.length);
        for (Class<?> implementation : permitted) {
            assertFalse(Modifier.isPublic(implementation.getModifiers()));
            assertFalse(Modifier.isProtected(implementation.getModifiers()));
        }
        Set<String> actualNames = new HashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            actualNames.add(method.getName());
            assertTrue(Modifier.isPublic(method.getModifiers()));
            assertTrue(Modifier.isAbstract(method.getModifiers()));
            assertFalse(method.isSynthetic());
        }
        assertEquals(methodNames, actualNames);
        assertEquals(methodNames.size(), type.getDeclaredMethods().length);
    }

    private static void assertMethod(Class<?> type, String name, Class<?> returnType, Class<?>... parameterTypes) {
        Method method;
        try {
            method = type.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
        assertEquals(returnType, method.getReturnType());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isAbstract(method.getModifiers()));
        assertFalse(method.isSynthetic());
    }

    private static void assertFacadeMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
        Method method;
        try {
            method = HeadlessRendering.class.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
        assertEquals(returnType, method.getReturnType());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertFalse(method.isSynthetic());
    }
}
