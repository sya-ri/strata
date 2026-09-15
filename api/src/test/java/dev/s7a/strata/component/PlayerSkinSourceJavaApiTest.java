package dev.s7a.strata.component;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies profile selectors preserve the native Java UUID boundary.
 */
final class PlayerSkinSourceJavaApiTest {
    @Test
    void profileSelectorRetainsTheNativeJavaIdentifier() {
        UUID profileId = new UUID(Long.MIN_VALUE, -1L);
        PlayerSkinSource.Uuid source = new PlayerSkinSource.Uuid(profileId);
        assertSame(profileId, source.getValue());
    }
}
