import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.process.CommandLineArgumentProvider
import java.nio.file.Files
import java.nio.file.LinkOption

dependencies {
    implementation(project(":runtime:headless"))
    implementation(project(":runtime:minecraft"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
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
val checkStaging = layout.buildDirectory.dir("component-showcase/check")
val generateStaging = layout.buildDirectory.dir("component-showcase/generate")
val repositoryRoot = providers.provider { rootProject.layout.projectDirectory }
val parityProject = rootProject.project(":integration:minecraft-fabric-26.2")
val parityOutput = parityProject.layout.buildDirectory.dir("minecraft-parity")
val skillCheckStaging = layout.buildDirectory.dir("strata-skill/check")
val skillGenerateStaging = layout.buildDirectory.dir("strata-skill/generate")
val skillExampleSources = layout.projectDirectory.dir("src/skillExamples/kotlin")
val runtimeVersionBuildInputs =
    rootProject.fileTree(rootProject.layout.projectDirectory.dir("runtime")) {
        include("minecraft-fabric-*/build.gradle.kts")
        exclude("**/build/**", "**/.gradle/**", "**/out/**")
    }

class ShowcaseArgumentProvider(
    private val repositoryRoot: Provider<Directory>,
    private val moduleBuildRoot: Provider<Directory>,
    private val stagingRoot: Provider<Directory>,
    private val parityRoot: Provider<Directory>,
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
            add(parityRoot.get().asFile.absolutePath)
            addAll(classDirectories.map { file -> file.absolutePath })
        }
    }
}

fun JavaExec.configureShowcaseLauncher(
    mainClassName: String,
    staging: Provider<Directory>,
    synchronizeSource: Boolean,
) {
    dependsOn(":api:classes", ":runtime:minecraft:classes", ":runtime:headless:classes", ":integration:minecraft-fabric-26.2:runClientGameTest", "classes")
    mainClass.set(mainClassName)
    classpath = sourceSets.main.get().runtimeClasspath
    argumentProviders.add(
        ShowcaseArgumentProvider(
            repositoryRoot,
            layout.buildDirectory,
            staging,
            parityOutput,
            apiMainClasses,
        ),
    )
    inputs.dir(showcaseSources)
    inputs.dir(parityOutput)
    inputs.files(apiMainClasses)
    outputs.dir(staging)
    outputs.upToDateWhen { false }
    if (synchronizeSource.not()) {
        inputs.file(rootProject.layout.projectDirectory.file("README.md"))
        inputs.file(rootProject.layout.projectDirectory.file("docs/components.md"))
        inputs.files(rootProject.layout.projectDirectory.dir("docs/components"))
    }
}

val generateComponentShowcase =
    tasks.register<JavaExec>("generateComponentShowcase") {
        group = "documentation"
        description = "Verifies loaded Minecraft parity and synchronizes the checked component showcase."
        configureShowcaseLauncher("dev.s7a.strata.integration.docs.ComponentShowcaseGenerator", generateStaging, true)
    }

val checkComponentShowcase =
    tasks.register<JavaExec>("checkComponentShowcase") {
        group = "verification"
        description = "Verifies loaded Minecraft parity and checks showcase files without changing source files."
        configureShowcaseLauncher("dev.s7a.strata.integration.docs.ComponentShowcaseChecker", checkStaging, false)
    }

class StrataSkillArgumentProvider(
    private val repositoryRoot: Provider<Directory>,
    private val stagingRoot: Provider<Directory>,
    private val exampleSourceRoot: Directory,
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
    argumentProviders.add(StrataSkillArgumentProvider(repositoryRoot, staging, skillExampleSources, apiMainClasses))
    inputs.dir(skillExampleSources)
    inputs.dir(rootProject.layout.projectDirectory.dir("api/src/main/kotlin"))
    inputs.files(runtimeVersionBuildInputs)
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

tasks.named("check") {
    dependsOn(checkComponentShowcase)
    dependsOn(checkStrataSkill, checkStrataSkillExampleClasspath, checkDocumentationLinks)
}
