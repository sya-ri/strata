import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

/**
 * Selected official client dependency and font-capability contracts exercised without Minecraft classes or a graphics context.
 * Versions are read from the shared catalog; each contract owns an isolated native test worker.
 */
enum class FontTestContract(
    val minecraftAlias: String,
    val lwjglAlias: String,
    val icuAlias: String,
    val gsonAlias: String,
    val rasterizer: Rasterizer,
    val javaAlias: String,
    val unsafeCore: Boolean = false,
    val patchedMacOsFreeType: Boolean = false,
    val providerFilters: Boolean = true,
    val packOverlays: Boolean = true,
    val minorPackFormats: Boolean = true,
    val interleavedShadows: Boolean = true,
    val fractionalUnihexAdvance: Boolean = false,
    val rejectMalformedOverlayMetadata: Boolean = false,
    val bakedGlyphMetrics: Boolean = false,
    val saturatingCeil: Boolean = false,
    val preparedTextBounds: Boolean = false,
    val nativeOracle: NativeOracle? = null,
) {
    Minecraft120(
        "minecraft120", "lwjgl-minecraft-stb-early", "icu-minecraft-early", "gson-minecraft-early", Rasterizer.Stb, "java-baseline",
        providerFilters = false, packOverlays = false, minorPackFormats = false, interleavedShadows = false, nativeOracle = NativeOracle.Legacy,
    ),
    Minecraft1202(
        "minecraft1202", "lwjgl-minecraft-stb", "icu-minecraft-baseline", "gson-minecraft", Rasterizer.Stb, "java-baseline",
        providerFilters = false, minorPackFormats = false, interleavedShadows = false,
    ),
    Minecraft1205(
        "minecraft1205", "lwjgl-minecraft-freetype", "icu-minecraft-baseline", "gson-minecraft", Rasterizer.FreeType, "java-minecraft121",
        patchedMacOsFreeType = true, minorPackFormats = false, interleavedShadows = false, nativeOracle = NativeOracle.Legacy,
    ),
    Minecraft1214(
        "minecraft1214", "lwjgl-minecraft-freetype", "icu-minecraft-later", "gson-minecraft-later", Rasterizer.FreeType, "java-minecraft121",
        patchedMacOsFreeType = true, minorPackFormats = false,
    ),
    Minecraft1216(
        "minecraft1216", "lwjgl-minecraft-freetype", "icu-minecraft-later", "gson-minecraft-later", Rasterizer.FreeType, "java-minecraft121",
        patchedMacOsFreeType = true, minorPackFormats = false, preparedTextBounds = true,
    ),
    Minecraft12111(
        "minecraft12111", "lwjgl-minecraft-freetype", "icu-minecraft-261", "gson-minecraft-261", Rasterizer.FreeType, "java-minecraft121",
        patchedMacOsFreeType = true, bakedGlyphMetrics = true, preparedTextBounds = true,
    ),
    Minecraft261(
        "minecraft261", "lwjgl-minecraft-modern", "icu-minecraft-261", "gson-minecraft-261", Rasterizer.FreeType, "java-minecraft",
        rejectMalformedOverlayMetadata = true, bakedGlyphMetrics = true, saturatingCeil = true, preparedTextBounds = true,
    ),
    Minecraft262(
        "minecraft262", "lwjgl-minecraft-modern", "icu-minecraft-262", "gson-minecraft-262", Rasterizer.FreeType, "java-minecraft",
        unsafeCore = true, fractionalUnihexAdvance = true, rejectMalformedOverlayMetadata = true, bakedGlyphMetrics = true, saturatingCeil = true, preparedTextBounds = true, nativeOracle = NativeOracle.Current,
    ),
    ;

    /** Native rasterizer selected by the target's font provider. */
    enum class Rasterizer { Stb, FreeType }

    /** Java binding and corresponding native library needed by the CPU font backend. */
    enum class Binding(val module: String) { Core("lwjgl"), Stb("lwjgl-stb"), FreeType("lwjgl-freetype") }

    /** Existing loaded test output roots that contain the independent native provider and Text oracle. */
    enum class NativeOracle(val outputDirectory: String) { Legacy("minecraft-verification"), Current("minecraft-parity") }

    /** Returns every CPU binding used by this contract, including STB image decoding. */
    fun bindings(): List<Binding> =
        when (rasterizer) {
            Rasterizer.Stb -> listOf(Binding.Core, Binding.Stb)
            Rasterizer.FreeType -> listOf(Binding.Core, Binding.Stb, Binding.FreeType)
        }

    /** Selects Minecraft's patched Intel macOS FreeType artifact only for the contracts that ship it. */
    fun nativeClassifier(binding: Binding, selected: String): String =
        if (binding == Binding.FreeType && patchedMacOsFreeType && selected == "natives-macos") "natives-macos-patch" else selected

    /** Selects the native core API artifact without pulling its unclassified counterpart transitively. */
    fun bindingClassifier(binding: Binding): String = if (binding == Binding.Core && unsafeCore) ":unsafe" else ""

    /** Identifies the contracts whose newer Java native binding requires explicit native access. */
    fun requiresNativeAccess(): Boolean = this == Minecraft261 || this == Minecraft262
}

val fontCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val fontSourceSets = extensions.getByType<SourceSetContainer>()
val fontToolchains = extensions.getByType<JavaToolchainService>()
val verifyOfflineFontParity = tasks.register("verifyOfflineFontParity") {
    group = "verification"
    description = "Recreates representative native font evidence and compares it with fresh isolated offline worker images."
}
val fontNativeClassifier =
    providers.gradleProperty("strata.fontNatives").orElse(
        providers.provider {
            val operatingSystem = System.getProperty("os.name")
            val architecture = System.getProperty("os.arch")
            val platform = when {
                operatingSystem.startsWith("Windows") -> "windows"
                operatingSystem.startsWith("Mac") -> "macos"
                operatingSystem.startsWith("Linux") -> "linux"
                else -> error("Set strata.fontNatives for this operating system.")
            }
            val suffix = when (architecture) {
                "amd64", "x86_64" -> ""
                "aarch64", "arm64" -> "-arm64"
                "x86", "i386" -> "-x86"
                "arm", "arm32", "armv7l" -> "-arm32"
                else -> error("Set strata.fontNatives for this architecture.")
            }
            "natives-$platform$suffix"
        },
    ).get()
require(fontNativeClassifier.matches(Regex("natives-[a-z0-9-]+"))) { "strata.fontNatives must name a native artifact classifier." }

// Why: an isolated worker must contain exactly the target's native generation, not a Gradle conflict-selected mixture.
val fontDependencyGroups = setOf("org.lwjgl", "com.ibm.icu", "com.google.code.gson")
val portableFontTestRuntime =
    configurations.named("testRuntimeClasspath").map { configuration ->
        configuration.incoming.artifactView {
            componentFilter { component ->
                when (component) {
                    is ModuleComponentIdentifier -> (component.group in fontDependencyGroups).not()
                    else -> true
                }
            }
        }.files
    }

/** Resolves a pinned version alias without copying external versions into this script. */
fun fontVersion(alias: String): String = fontCatalog.findVersion(alias).orElseThrow().requiredVersion

/** Registers an isolated CPU test runtime with no inherited native bindings or game dependencies. */
fun fontRuntime(contract: FontTestContract) {
    val minecraftVersion = fontVersion(contract.minecraftAlias)
    val bindings = fontVersion(contract.lwjglAlias)
    val icu = fontVersion(contract.icuAlias)
    val gson = fontVersion(contract.gsonAlias)
    val javaVersion = fontVersion(contract.javaAlias).toInt()
    val runtime = configurations.create("fontRuntime${contract.name}") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
    contract.bindings().forEach { binding ->
        dependencies.add(runtime.name, "org.lwjgl:${binding.module}:$bindings${contract.bindingClassifier(binding)}")
        dependencies.add(runtime.name, "org.lwjgl:${binding.module}:$bindings:${contract.nativeClassifier(binding, fontNativeClassifier)}")
    }
    dependencies.add(runtime.name, "com.ibm.icu:icu4j:$icu")
    dependencies.add(runtime.name, "com.google.code.gson:gson:$gson")
    val workerClasspath = files(fontSourceSets.named("test").get().output, fontSourceSets.named("main").get().output, portableFontTestRuntime, runtime)
    val workerLauncher = fontToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(javaVersion)) }
    val offlineOutput = layout.buildDirectory.dir("font-offline/$minecraftVersion")
    val verification = tasks.register<Test>("fontTest${contract.name}") {
        group = "verification"
        description = "Runs CPU font tests against the isolated ${contract.name} dependency contract without Minecraft or a graphics context."
        dependsOn(tasks.named("testClasses"))
        shouldRunAfter(tasks.named("test"))
        testClassesDirs = fontSourceSets.named("test").get().output.classesDirs
        classpath = workerClasspath
        maxParallelForks = 1
        outputs.dir(offlineOutput)
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        systemProperty("strata.minecraftVersion", minecraftVersion)
        systemProperty("strata.fontOfflineOutput", offlineOutput.get().asFile.absolutePath)
        systemProperty("strata.fontRasterizer", contract.rasterizer.name)
        systemProperty("strata.fontLwjglVersion", bindings)
        systemProperty("strata.fontIcuVersion", icu)
        systemProperty("strata.fontGsonVersion", gson)
        systemProperty("strata.fontCoreClassifier", contract.bindingClassifier(FontTestContract.Binding.Core).removePrefix(":"))
        systemProperty("strata.fontJavaVersion", javaVersion)
        systemProperty("strata.fontNativeClassifier", fontNativeClassifier)
        systemProperty("strata.fontProviderFilters", contract.providerFilters)
        systemProperty("strata.fontPackOverlays", contract.packOverlays)
        systemProperty("strata.fontMinorPackFormats", contract.minorPackFormats)
        systemProperty("strata.fontInterleavedShadows", contract.interleavedShadows)
        systemProperty("strata.fontFractionalUnihexAdvance", contract.fractionalUnihexAdvance)
        systemProperty("strata.fontRejectMalformedOverlayMetadata", contract.rejectMalformedOverlayMetadata)
        systemProperty("strata.fontBakedGlyphMetrics", contract.bakedGlyphMetrics)
        systemProperty("strata.fontSaturatingCeil", contract.saturatingCeil)
        systemProperty("strata.fontPreparedTextBounds", contract.preparedTextBounds)
        javaLauncher.set(workerLauncher)
        if (contract.requiresNativeAccess()) jvmArgs("--enable-native-access=ALL-UNNAMED")
        useJUnitPlatform { excludeTags("font-offline-parity") }
    }
    tasks.named("check") { dependsOn(verification) }
    contract.nativeOracle?.let { oracle ->
        val nativeProjectPath = ":integration:minecraft-fabric-$minecraftVersion"
        val nativeRunner = "$nativeProjectPath:runClientGameTest"
        val nativeProject = project(nativeProjectPath)
        nativeProject.tasks.matching { it.name == "runClientGameTest" }.configureEach {
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
        }
        val nativeOutput = nativeProject.layout.buildDirectory.dir("${oracle.outputDirectory}/font-parity")
        val comparisonOutput = layout.buildDirectory.dir("font-offline-parity/$minecraftVersion")
        val comparison = tasks.register<Test>("compareOfflineFont${contract.name}") {
            group = "verification"
            description = "Requires exact native glyph evidence and independently verified GPU classifications for fresh $minecraftVersion offline images."
            dependsOn(verification)
            mustRunAfter(nativeRunner)
            testClassesDirs = fontSourceSets.named("test").get().output.classesDirs
            classpath = workerClasspath
            maxParallelForks = 1
            inputs.dir(nativeOutput)
            inputs.dir(offlineOutput)
            outputs.dir(comparisonOutput)
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
            systemProperty("strata.minecraftVersion", minecraftVersion)
            systemProperty("strata.fontOfflineOutput", offlineOutput.get().asFile.absolutePath)
            systemProperty("strata.fontNativeOutput", nativeOutput.get().asFile.absolutePath)
            systemProperty("strata.fontComparisonOutput", comparisonOutput.get().asFile.absolutePath)
            javaLauncher.set(workerLauncher)
            if (contract.requiresNativeAccess()) jvmArgs("--enable-native-access=ALL-UNNAMED")
            useJUnitPlatform { includeTags("font-offline-parity") }
        }
        verifyOfflineFontParity.configure { dependsOn(nativeRunner, comparison) }
    }
}

FontTestContract.entries.forEach(::fontRuntime)

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("font-offline-scene", "font-offline-parity") }
}

dependencies {
    val baseline = FontTestContract.Minecraft1205
    baseline.bindings().forEach { binding ->
        val native = baseline.nativeClassifier(binding, fontNativeClassifier)
        add("testRuntimeOnly", "org.lwjgl:${binding.module}:${fontVersion(baseline.lwjglAlias)}:$native")
    }
}
