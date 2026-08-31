package dev.s7a.strata.gradle.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Verifies that the stable Maven artifact inventory resolves exactly one requested release version. */
internal class MavenReleaseCoordinatesTest {
    @Test
    fun `resolves every artifact against the requested version`() {
        assertEquals(
            listOf(
                "dev.s7a.strata:strata-api:1.2.3",
                "dev.s7a.strata:strata-runtime-core:1.2.3",
            ),
            MavenReleaseCoordinates.resolve(
                listOf(
                    "dev.s7a.strata:strata-api",
                    "dev.s7a.strata:strata-runtime-core",
                ),
                "1.2.3",
            ),
        )
    }

    @Test
    fun `rejects versioned malformed empty and duplicate inventory entries`() {
        listOf(
            emptyList(),
            listOf("dev.s7a.strata:strata-api:1.2.3"),
            listOf("dev.s7a.strata:"),
            listOf("dev.s7a.strata:strata api"),
            listOf("dev.s7a.strata/unsafe:strata-api"),
            listOf("dev..s7a.strata:strata-api"),
            listOf("..:strata-api"),
            listOf("dev.s7a.strata:.."),
            listOf("dev.s7a.strata:strata-api", "dev.s7a.strata:strata-api"),
        ).forEach { inventory ->
            assertThrows(IllegalStateException::class.java) {
                MavenReleaseCoordinates.resolve(inventory, "1.2.3")
            }
        }
    }

    @Test
    fun `rejects malformed release versions`() {
        listOf("", "1.2", "1.2.3:unsafe", "1.2.3 unsafe").forEach { version ->
            assertThrows(IllegalStateException::class.java) {
                MavenReleaseCoordinates.resolve(listOf("dev.s7a.strata:strata-api"), version)
            }
        }
    }
}
