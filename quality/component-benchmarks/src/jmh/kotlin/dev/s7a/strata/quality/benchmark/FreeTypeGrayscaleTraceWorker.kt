package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonParser
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.freetype.FT_Face
import org.lwjgl.util.freetype.FreeType
import java.nio.file.Files
import java.nio.file.Path

/**
 * Untimed external-observer worker using the original forty converter, glyph, Text and control operations.
 * Preparation and complete output checks surround the same operation calls used by the unchanged JMH fixture.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object FreeTypeGrayscaleTraceWorker {
    /**
     * Loads the pinned classes, then runs one original row or one independently counted qualification.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 2)
        val request = JsonParser.parseString(Files.readString(Path.of(args[0]))).asJsonObject
        request.getAsJsonObject("required_classes").keySet().forEach { Class.forName(it, false, ClassLoader.getSystemClassLoader()) }
        val id = args[1]
        when {
            id.startsWith("Q") -> qualify(id)
            id.startsWith("Converter") -> converter(id)
            id.startsWith("Glyph-") -> glyph(id.removePrefix("Glyph-"), false, id)
            id.startsWith("Text-") -> glyph(id.removePrefix("Text-"), true, id)
            else -> control(FreeTypeGrayscaleBenchmark.Control.valueOf(id), id)
        }
    }

    private fun converter(id: String) {
        val match = checkNotNull(Regex("Converter(1|8|16|64|256)(TightPositive|PaddedPositive|TightNegative|PaddedNegative)").matchEntire(id))
        val axis = match.groupValues[1].toInt()
        val layout = FreeTypeGrayscaleBenchmark.Layout.valueOf(match.groupValues[2])
        val (image, retained) =
            FreeTypeBitmapFixture(axis, layout).use { owner ->
                val expected = owner.expected()
                begin(id)
                val actual = owner.image()
                end(id)
                check(actual.copyArgb().contentEquals(expected))
                owner.overwrite()
                check(actual.copyArgb().contentEquals(expected))
                actual to expected
            }
        check(image.copyArgb().contentEquals(retained))
        image.copyArgb().fill(0)
        check(image.copyArgb().contentEquals(retained))
    }

    private fun glyph(
        name: String,
        text: Boolean,
        id: String,
    ) {
        val fixture = FreeTypeGlyphFixture.valueOf(name)
        val reference = FreeTypeScalarOracle.glyph(fixture).glyph
        FreeTypeGlyphOwner(fixture).use { owner ->
            if (text) {
                val expected = owner.referenceText(reference)
                begin(id)
                val actual = owner.dirtyText()
                end(id)
                check(actual.size == expected.size && actual.drawCommands == expected.drawCommands && actual.semantics == expected.semantics)
                check(
                    rasterizeHeadless(actual.drawCommands, FreeTypeGlyphOwner.viewport).copyArgb().contentEquals(
                        rasterizeHeadless(expected.drawCommands, FreeTypeGlyphOwner.viewport).copyArgb(),
                    ),
                )
            } else {
                begin(id)
                val actual = owner.glyph()
                end(id)
                check(actual.copy(image = null) == reference.copy(image = null))
                check(checkNotNull(actual.image).size == checkNotNull(reference.image).size)
                check(checkNotNull(actual.image).copyArgb().contentEquals(checkNotNull(reference.image).copyArgb()))
            }
        }
    }

    private fun control(
        control: FreeTypeGrayscaleBenchmark.Control,
        id: String,
    ) {
        FreeTypeControlFixture(control).use { owner ->
            begin(id)
            val actual = owner.sample()
            end(id)
            when (control) {
                FreeTypeGrayscaleBenchmark.Control.MissingFreeTypeGlyph -> check(actual == true)
                FreeTypeGrayscaleBenchmark.Control.EmptyFreeTypeGlyph -> check((actual as MinecraftFontGlyph).image == null)
                FreeTypeGrayscaleBenchmark.Control.MalformedConverterInput -> check((actual as List<*>).size == 3)
                else -> check(actual.toString().isNotEmpty())
            }
        }
    }

    private fun qualify(id: String) {
        val count =
            when (id) {
                "QZero", "QMissingEnd" -> 0
                "QOne" -> 1
                "QThree" -> 3
                else -> error("Unknown independent qualification")
            }
        val buffer = MemoryUtil.memAlloc(3)
        try {
            buffer.put(0, 11).put(1, 23).put(2, 47)
            LwjglMinecraftFontBackendFactory.open(FontRasterAssets.compatibility(MinecraftTrueTypeRasterizer.FreeType)).use { backend ->
                backend.openTrueType(FreeTypeGrayscaleAssets.font(), FreeTypeGlyphFixture.SmallGlyphOne.settings).use { managed ->
                    val delegate = FreeTypeGrayscaleAssets.delegate(managed)
                    val native = delegate.javaClass.getDeclaredField("face").apply { isAccessible = true }.get(delegate) as FT_Face
                    val index = FreeType.FT_Get_Char_Index(native, 0x41)
                    check(index != 0)
                    begin(id)
                    repeat(count) { offset ->
                        check(buffer[offset].toInt() == listOf(11, 23, 47)[offset])
                        check(FreeType.FT_Load_Glyph(native, index, FreeType.FT_LOAD_BITMAP_METRICS_ONLY) == 0)
                    }
                    if (id != "QMissingEnd") end(id)
                }
            }
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    private fun begin(id: String) {
        println("FreeType trace begin $id")
    }

    private fun end(id: String) {
        println("FreeType trace end $id")
    }
}
