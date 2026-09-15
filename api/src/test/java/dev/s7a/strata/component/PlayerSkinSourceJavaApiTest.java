package dev.s7a.strata.component;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import kotlin.uuid.UuidKt;
import org.junit.jupiter.api.Test;

/**
 * Verifies profile selectors remain usable from Java through standard UUID conversions.
 */
final class PlayerSkinSourceJavaApiTest {
    @Test
    void profileSelectorPreservesAllBitsAcrossTheJavaBoundary() {
        UUID profileId = new UUID(Long.MIN_VALUE, -1L);
        PlayerSkinSource.Uuid source = new PlayerSkinSource.Uuid(UuidKt.toKotlinUuid(profileId));
        assertEquals(profileId, UuidKt.toJavaUuid(source.getValue()));
    }
}
