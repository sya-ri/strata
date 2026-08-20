package dev.s7a.strata.runtime.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.s7a.strata.geometry.IntRect;
import dev.s7a.strata.geometry.IntSize;
import dev.s7a.strata.render.DrawImage;
import dev.s7a.strata.render.DrawImages;
import dev.s7a.strata.runtime.render.DrawCommand;
import org.junit.jupiter.api.Test;

/**
 * Verifies Java callers can construct the public image and blit command contracts.
 */
final class DrawImageJavaApiTest {
    @Test
    void javaCanConstructBlitImage() {
        DrawImage image = DrawImages.createDrawImage(new IntSize(1, 1), new int[] {0xFF123456});
        DrawCommand.BlitImage command =
            new DrawCommand.BlitImage(image, new IntRect(0, 0, 1, 1), new IntRect(2, 3, 4, 5));

        assertSame(image, command.getImage());
        assertEquals(new IntRect(0, 0, 1, 1), command.getSource());
        assertEquals(new IntRect(2, 3, 4, 5), command.getDestination());
    }
}
