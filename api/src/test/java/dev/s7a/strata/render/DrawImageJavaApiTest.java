package dev.s7a.strata.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.s7a.strata.geometry.IntSize;
import org.junit.jupiter.api.Test;

/**
 * Verifies Java callers can construct and read the public image contract.
 */
final class DrawImageJavaApiTest {
    @Test
    void javaCanCreateAndUseDrawImage() {
        DrawImage image = DrawImages.createDrawImage(new IntSize(1, 1), new int[] {0xFF123456});

        assertEquals(new IntSize(1, 1), image.getSize());
        assertEquals(0xFF123456, image.argbAt(0, 0));
        assertArrayEquals(new int[] {0xFF123456}, image.copyArgb());
    }
}
