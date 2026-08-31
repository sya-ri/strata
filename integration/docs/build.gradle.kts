import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.nio.file.Files
import java.nio.file.LinkOption

val showcaseLwjglVersion = libs.versions.lwjgl.minecraft.modern.get()
val showcaseCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val showcaseGsonVersion = showcaseCatalog.findVersion("gson-minecraft-262").orElseThrow().requiredVersion
val showcaseIcuVersion = showcaseCatalog.findVersion("icu-minecraft-262").orElseThrow().requiredVersion
val showcaseNativeClassifier =
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
require(showcaseNativeClassifier.matches(Regex("natives-[a-z0-9-]+"))) {
    "strata.fontNatives must name a native artifact classifier."
}

dependencies {
    implementation(project(":runtime:headless"))
    implementation(project(":runtime:minecraft"))
    implementation(project(":runtime:minecraft-fonts-lwjgl"))
    implementation("com.google.code.gson:gson:$showcaseGsonVersion") { isTransitive = false }
    runtimeOnly("com.ibm.icu:icu4j:$showcaseIcuVersion") { isTransitive = false }
    runtimeOnly("org.lwjgl:lwjgl:$showcaseLwjglVersion:unsafe") { isTransitive = false }
    runtimeOnly("org.lwjgl:lwjgl-stb:$showcaseLwjglVersion") { isTransitive = false }
    runtimeOnly("org.lwjgl:lwjgl-freetype:$showcaseLwjglVersion") { isTransitive = false }
    listOf("lwjgl", "lwjgl-stb", "lwjgl-freetype").forEach { binding ->
        runtimeOnly("org.lwjgl:$binding:$showcaseLwjglVersion:$showcaseNativeClassifier") { isTransitive = false }
    }
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val showcaseLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(libs.versions.java.minecraft.get().toInt()))
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(showcaseLauncher)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("java.awt.headless", "true")
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(showcaseLauncher)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("java.awt.headless", "true")
}

val skillExamples = sourceSets.create("skillExamples")

dependencies {
    add(skillExamples.compileOnlyConfigurationName, project(":api"))
}

val apiMainClasses =
    rootProject.project(":api").extensions
        .getByType<SourceSetContainer>()
        .named("main")
        .map { sourceSet -> sourceSet.output.classesDirs }
val showcaseSources = rootProject.layout.projectDirectory.dir("integration/minecraft-fabric-unobfuscated/src/gametest/kotlin")
val showcaseFixtureResources = rootProject.layout.projectDirectory.dir("integration/minecraft-fabric-unobfuscated/src/gametest/resources")
val showcaseExampleSources = objects.sourceDirectorySet("showcaseExamples", "API-only showcase examples").apply {
    srcDir(showcaseSources)
    include("**/*Example.kt")
}
extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("main") {
        kotlin.source(showcaseExampleSources)
    }
}

val checkStaging = layout.buildDirectory.dir("component-showcase/check")
val generateStaging = layout.buildDirectory.dir("component-showcase/generate")
val repositoryRoot = providers.provider { rootProject.layout.projectDirectory }
val parityOutput = providers.provider {
    rootProject.project(":integration:minecraft-fabric-26.2").layout.buildDirectory.dir("minecraft-parity").get()
}
val nativeInventoryPng = rootProject.layout.projectDirectory.file("docs/evidence/minecraft-26.2-inventory.png")
val nativeInventoryReceipt = rootProject.layout.projectDirectory.file("docs/evidence/minecraft-26.2-inventory.properties")
val nativeParityReceipt = rootProject.layout.projectDirectory.file("docs/evidence/minecraft-26.2-parity.properties")
val skillCheckStaging = layout.buildDirectory.dir("strata-skill/check")
val skillGenerateStaging = layout.buildDirectory.dir("strata-skill/generate")
val skillExampleSources = layout.projectDirectory.dir("src/skillExamples/kotlin")
val runtimeVersionBuildInputs =
    rootProject.fileTree(rootProject.layout.projectDirectory.dir("runtime")) {
        include("minecraft-fabric-*/build.gradle.kts")
        exclude("**/build/**", "**/.gradle/**", "**/out/**")
    }

/**
 * Selects one explicit read-only input, or lazily resolves its Loom asset publication without adding it to a JVM classpath.
 * Supplying all four paths avoids any dependency on the Minecraft integration project.
 */
fun showcaseAssetInput(propertyName: String, configurationName: String): FileCollection {
    val supplied = providers.gradleProperty(propertyName)
    if (supplied.isPresent) {
        return files(supplied.map { value ->
            require(value.isNotBlank()) { "$propertyName must name a read-only Minecraft asset input." }
            rootProject.file(value)
        })
    }
    val input = configurations.create("${configurationName}Input") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
    dependencies.add(
        input.name,
        dependencies.project(mapOf("path" to ":integration:minecraft-fabric-26.2", "configuration" to configurationName)),
    )
    return input
}

val showcaseClientJar = showcaseAssetInput("strata.showcase.clientJar", "showcaseClientJar")
val showcaseAssetIndex = showcaseAssetInput("strata.showcase.assetIndex", "showcaseAssetIndex")
val showcaseAssetObjects = showcaseAssetInput("strata.showcase.assetObjects", "showcaseAssetObjects")
val showcaseVersionManifest = showcaseAssetInput("strata.showcase.versionManifest", "showcaseVersionManifest")
val showcaseAssetInputs = files(showcaseClientJar, showcaseAssetIndex, showcaseAssetObjects, showcaseVersionManifest)

class ShowcaseArgumentProvider(
    private val repositoryRoot: Provider<Directory>,
    private val moduleBuildRoot: Provider<Directory>,
    private val stagingRoot: Provider<Directory>,
    private val clientJar: FileCollection,
    private val assetIndex: FileCollection,
    private val assetObjects: FileCollection,
    private val versionManifest: FileCollection,
    private val inventoryPng: File,
    private val inventoryReceipt: File,
    private val componentClasses: Provider<FileCollection>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val classDirectories =
            componentClasses
                .get()
                .files
                .filter { file -> file.exists() }
                .sortedBy { file -> file.absolutePath }
        require(classDirectories.isNotEmpty()) { "API component class output has no existing classes directory." }
        classDirectories.forEach { directory ->
            require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "API component class output is not a directory: ${directory.absolutePath}"
            }
            require(Files.isSymbolicLink(directory.toPath()).not()) {
                "API component class output is symbolic: ${directory.absolutePath}"
            }
        }
        return buildList {
            add(repositoryRoot.get().asFile.absolutePath)
            add(moduleBuildRoot.get().asFile.absolutePath)
            add(stagingRoot.get().asFile.absolutePath)
            add(clientJar.singleFile.absolutePath)
            add(assetIndex.singleFile.absolutePath)
            add(assetObjects.singleFile.absolutePath)
            add(versionManifest.singleFile.absolutePath)
            add(inventoryPng.absolutePath)
            add(inventoryReceipt.absolutePath)
            addAll(classDirectories.map { file -> file.absolutePath })
        }
    }
}

fun JavaExec.configureShowcaseLauncher(
    mainClassName: String,
    staging: Provider<Directory>,
    synchronizeSource: Boolean,
) {
    dependsOn(":api:classes", "classes", showcaseAssetInputs)
    mustRunAfter("generateMinecraftShowcaseEvidence")
    mainClass.set(mainClassName)
    classpath = sourceSets.main.get().runtimeClasspath
    argumentProviders.add(
        ShowcaseArgumentProvider(
            repositoryRoot,
            layout.buildDirectory,
            staging,
            showcaseClientJar,
            showcaseAssetIndex,
            showcaseAssetObjects,
            showcaseVersionManifest,
            nativeInventoryPng.asFile,
            nativeInventoryReceipt.asFile,
            apiMainClasses,
        ),
    )
    inputs.files(showcaseExampleSources).withPropertyName("showcaseExamples").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(showcaseFixtureResources).withPropertyName("showcaseFixtureResources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(showcaseClientJar).withPropertyName("minecraftClientJar").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(showcaseAssetIndex).withPropertyName("minecraftAssetIndex").withPathSensitivity(PathSensitivity.NONE)
    // The shared object store includes other versions; the launcher hashes every object it actually consumes.
    inputs.property("minecraftAssetObjectsLocation", providers.provider { showcaseAssetObjects.singleFile.absolutePath })
    inputs.files(showcaseVersionManifest).withPropertyName("minecraftVersionManifest").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(nativeInventoryPng).withPropertyName("nativeInventoryPng").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(nativeInventoryReceipt).withPropertyName("nativeInventoryReceipt").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(apiMainClasses).withNormalizer(ClasspathNormalizer::class.java)
    outputs.dir(staging)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    if (synchronizeSource.not()) {
        inputs.file(rootProject.layout.projectDirectory.file("README.md"))
        inputs.file(rootProject.layout.projectDirectory.file("docs/components.md"))
        inputs.files(rootProject.layout.projectDirectory.dir("docs/components"))
    }
}

val generateComponentShowcase =
    tasks.register<JavaExec>("generateComponentShowcase") {
        group = "documentation"
        description = "Renders the compiled component showcase on the CPU and synchronizes checked documentation without launching Minecraft."
        configureShowcaseLauncher("dev.s7a.strata.integration.docs.ComponentShowcaseGenerator", generateStaging, true)
    }

val checkComponentShowcase =
    tasks.register<JavaExec>("checkComponentShowcase") {
        group = "verification"
        description = "Renders the compiled component showcase on the CPU and checks documentation without Minecraft or source changes."
        configureShowcaseLauncher("dev.s7a.strata.integration.docs.ComponentShowcaseChecker", checkStaging, false)
    }

/**
 * Configures the separate loaded-game oracle boundary without making headless generation depend on it.
 */
fun JavaExec.configureMinecraftShowcaseParity(mode: String) {
    dependsOn(":integration:minecraft-fabric-26.2:runClientGameTest", "classes")
    mainClass.set("dev.s7a.strata.integration.docs.MinecraftShowcaseParityChecker")
    classpath = sourceSets.main.get().runtimeClasspath
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            repositoryRoot.get().asFile.absolutePath,
            parityOutput.get().asFile.absolutePath,
            checkStaging.get().asFile.absolutePath,
            mode,
        )
    })
    inputs.dir(parityOutput).withPropertyName("nativeParity").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

val generateMinecraftShowcaseEvidence = tasks.register<JavaExec>("generateMinecraftShowcaseEvidence") {
    group = "documentation"
    description = "Recreates the independent Minecraft showcase proof and native inventory fixture without requiring headless staging."
    configureMinecraftShowcaseParity("generate")
    outputs.files(nativeInventoryPng, nativeInventoryReceipt, nativeParityReceipt)
}

val checkMinecraftShowcaseParity = tasks.register<JavaExec>("checkMinecraftShowcaseParity") {
    group = "verification"
    description = "Compares fresh native Minecraft evidence with independently rendered headless showcase images."
    configureMinecraftShowcaseParity("check")
    dependsOn(checkComponentShowcase)
    inputs.dir(checkStaging).withPropertyName("headlessShowcase").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(nativeInventoryPng, nativeInventoryReceipt, nativeParityReceipt).withPropertyName("checkedNativeEvidence").withPathSensitivity(PathSensitivity.RELATIVE)
}

class StrataSkillArgumentProvider(
    private val repositoryRoot: Provider<Directory>,
    private val stagingRoot: Provider<Directory>,
    private val exampleSourceRoot: Directory,
    private val releaseVersion: Provider<String>,
    private val componentClasses: Provider<FileCollection>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val classDirectories =
            componentClasses
                .get()
                .files
                .filter(File::exists)
                .sortedBy(File::getAbsolutePath)
        require(classDirectories.isNotEmpty()) { "API component class output has no existing classes directory." }
        return buildList {
            add(repositoryRoot.get().asFile.absolutePath)
            add(stagingRoot.get().asFile.absolutePath)
            add(exampleSourceRoot.asFile.absolutePath)
            add(releaseVersion.get())
            addAll(classDirectories.map(File::getAbsolutePath))
        }
    }
}

fun JavaExec.configureStrataSkillLauncher(
    mainClassName: String,
    staging: Provider<Directory>,
    synchronizeSource: Boolean,
) {
    dependsOn(":api:classes", "compileSkillExamplesKotlin", "classes")
    mainClass.set(mainClassName)
    classpath = sourceSets.main.get().runtimeClasspath
    argumentProviders.add(
        StrataSkillArgumentProvider(
            repositoryRoot,
            staging,
            skillExampleSources,
            providers.provider { rootProject.version.toString() },
            apiMainClasses,
        ),
    )
    inputs.dir(skillExampleSources)
    inputs.dir(rootProject.layout.projectDirectory.dir("api/src/main/kotlin"))
    inputs.files(runtimeVersionBuildInputs)
    inputs.property("releaseVersion", rootProject.version.toString())
    inputs.file(rootProject.layout.projectDirectory.file("README.md"))
    inputs.files(apiMainClasses)
    outputs.dir(staging)
    outputs.upToDateWhen { false }
    if (synchronizeSource.not()) {
        inputs.dir(rootProject.layout.projectDirectory.dir("skills/strata"))
        inputs.file(rootProject.layout.projectDirectory.file("docs/modrinth-project.md"))
        inputs.file(rootProject.layout.projectDirectory.file("LICENSE"))
    }
}

val generateStrataSkill =
    tasks.register<JavaExec>("generateStrataSkill") {
        group = "documentation"
        description = "Generates the public Strata skill references from the compiled API and API-only examples."
        configureStrataSkillLauncher("dev.s7a.strata.integration.docs.StrataSkillGenerator", skillGenerateStaging, true)
    }

val checkStrataSkill =
    tasks.register<JavaExec>("checkStrataSkill") {
        group = "verification"
        description = "Checks the public Strata skill without changing tracked files."
        configureStrataSkillLauncher("dev.s7a.strata.integration.docs.StrataSkillChecker", skillCheckStaging, false)
    }

val checkStrataSkillExampleClasspath =
    tasks.register("checkStrataSkillExampleClasspath") {
        group = "verification"
        description = "Verifies that public-skill examples compile against only the API project."
        dependsOn("compileSkillExamplesKotlin")
        doLast {
            val projectDependencies =
                configurations
                    .getByName(skillExamples.compileClasspathConfigurationName)
                    .incoming
                    .resolutionResult
                    .allComponents
                    .mapNotNull { component -> (component.id as? ProjectComponentIdentifier)?.projectPath }
                    .filter { projectPath -> projectPath != project.path }
                    .toSet()
            require(projectDependencies == setOf(":api")) {
                "Strata skill example compile classpath contains project dependencies: $projectDependencies"
            }
        }
    }

val checkDocumentationLinks =
    tasks.register<JavaExec>("checkDocumentationLinks") {
        group = "verification"
        description = "Checks repository-local README, docs, and public-skill links."
        dependsOn("classes")
        mainClass.set("dev.s7a.strata.integration.docs.DocumentationLinkChecker")
        classpath = sourceSets.main.get().runtimeClasspath
        args(repositoryRoot.get().asFile.absolutePath)
        inputs.file(rootProject.layout.projectDirectory.file("README.md"))
        inputs.dir(rootProject.layout.projectDirectory.dir("docs"))
        inputs.dir(rootProject.layout.projectDirectory.dir("skills"))
        outputs.upToDateWhen { false }
    }

val dokkaPagesRoot = rootProject.layout.buildDirectory.dir("dokka/html")
val dokkaPagesInputs =
    dokkaPagesRoot.map { directory ->
        directory.asFileTree.matching {
            exclude("pages-public-urls.txt", "releases/**")
        }
    }
val pagesPublicUrlInventory = rootProject.layout.buildDirectory.file("dokka/html/pages-public-urls.txt")
val pagesRepositoryInputs =
    providers.provider {
        rootProject
            .fileTree(rootProject.layout.projectDirectory) {
                listOf(
                    "gradle",
                    "html",
                    "java",
                    "json",
                    "kt",
                    "kts",
                    "md",
                    "properties",
                    "toml",
                    "txt",
                    "xml",
                    "yaml",
                    "yml",
                ).forEach { extension ->
                    include("*.$extension", "**/*.$extension")
                }
                exclude(
                    ".git/**",
                    ".gradle/**",
                    "build/**",
                    "out/**",
                    "**/.git/**",
                    "**/.gradle/**",
                    "**/build/**",
                    "**/out/**",
                )
            }.files
            .filter(File::isFile)
            .sortedBy(File::getAbsolutePath)
    }
val generateDokkaPagesInventory =
    tasks.register<JavaExec>("generateDokkaPagesInventory") {
        group = "documentation"
        description = "Generates deterministic public URLs for the staged Dokka API site."
        dependsOn(rootProject.tasks.named("dokkaGenerate"), rootProject.tasks.named("stagePagesSourceRevision"), checkDocumentationLinks, "classes")
        mustRunAfter(rootProject.tasks.named("verifyGeneratedDokkaSourceLinks"))
        mainClass.set("dev.s7a.strata.integration.docs.PagesPublicUrlInventory")
        classpath = sourceSets.main.get().runtimeClasspath
        args(
            repositoryRoot.get().asFile.absolutePath,
            dokkaPagesRoot.get().asFile.absolutePath,
            pagesPublicUrlInventory.get().asFile.absolutePath,
        )
        inputs.files(pagesRepositoryInputs)
        inputs.files(dokkaPagesInputs).withPropertyName("dokkaSite").withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(pagesPublicUrlInventory)
        outputs.upToDateWhen { false }
    }

tasks.register<JavaExec>("checkDokkaPagesStaging") {
    group = "verification"
    description = "Checks the generated Dokka API site, source receipt, and advertised Pages URLs."
    dependsOn(
        generateDokkaPagesInventory,
        rootProject.tasks.named("verifyGeneratedDokkaSourceLinks"),
        "classes",
    )
    mainClass.set("dev.s7a.strata.integration.docs.PagesStagingChecker")
    classpath = sourceSets.main.get().runtimeClasspath
    args(
        repositoryRoot.get().asFile.absolutePath,
        dokkaPagesRoot.get().asFile.absolutePath,
        pagesPublicUrlInventory.get().asFile.absolutePath,
    )
    inputs.files(pagesRepositoryInputs)
    inputs.files(dokkaPagesInputs).withPropertyName("dokkaSite").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(pagesPublicUrlInventory)
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(checkComponentShowcase, checkMinecraftShowcaseParity)
    dependsOn(checkStrataSkill, checkStrataSkillExampleClasspath, checkDocumentationLinks)
}
