import dev.detekt.gradle.Detekt
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.w3c.dom.Element
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
}

group = "dev.s7a.strata.release.authoring"
val strataVersion = providers.gradleProperty("strataVersion").get()
require(strataVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?"))) {
    "strataVersion must be an exact semantic release version."
}
version = strataVersion

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("dev.s7a.strata:strata-api:$strataVersion")
    detektPlugins("dev.s7a.strata:strata-detekt-rules:$strataVersion")
}

val expectedRules =
    setOf(
        "StateCreatedDuringComposition",
        "UnusedComponentModifier",
        "DiscardedModifier",
        "MultipleModifierApplications",
        "ParentDataOnWrongParent",
        "StateMutationDuringComposition",
        "SubscriptionDuringComposition",
        "HostAccessDuringComposition",
        "InvalidRootCount",
    )
val guide = layout.projectDirectory.file("../../docs/guides/authoring-checks.md")
val imports = layout.projectDirectory.file("imports.txt")
val generatedExamples = layout.buildDirectory.dir("generated/authoring-examples")
val generateAuthoringExamples =
    tasks.register("generateAuthoringExamples") {
        inputs.files(guide, imports)
        outputs.dir(generatedExamples)
        doLast {
            val markdown = guide.asFile.readText()
            listOf("valid", "invalid").forEach { kind ->
                val snippets =
                    Regex("<!-- checked-example: $kind -->\\s+```kotlin\\s*\\n([\\s\\S]*?)\\n```")
                        .findAll(markdown).map { it.groupValues[1] }.toList()
                check(snippets.size == expectedRules.size) { "Each rule needs a checked $kind example." }
                val output = generatedExamples.get().file("$kind/Examples.kt").asFile
                output.parentFile.mkdirs()
                output.writeText("package authoring.$kind\n${imports.asFile.readText()}\n${snippets.joinToString("\n\n")}\n")
            }
        }
    }

sourceSets.main {
    kotlin.srcDir(generatedExamples.map { it.dir("valid") })
}
val invalidExamples = sourceSets.create("invalidExamples")
configurations[invalidExamples.implementationConfigurationName].extendsFrom(configurations.implementation.get())
kotlin.sourceSets[invalidExamples.name].kotlin.srcDir(generatedExamples.map { it.dir("invalid") })
tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generateAuthoringExamples)
}

detekt {
    config.setFrom(files("detekt.yml"))
    disableDefaultRuleSets.set(true)
}
tasks.withType<Detekt>().configureEach {
    dependsOn(generateAuthoringExamples)
}
val invalidReport = layout.buildDirectory.file("reports/detekt/invalid-examples.xml")
val checkInvalidExamples =
    tasks.named<Detekt>("detektInvalidExamples") {
        ignoreFailures.set(true)
        reports.checkstyle {
            required.set(true)
            outputLocation.set(invalidReport)
        }
    }

val verifyPublishedAuthoringRules =
    tasks.register("verifyPublishedAuthoringRules") {
        dependsOn("detektMain", "compileInvalidExamplesKotlin", checkInvalidExamples)
        inputs.file(invalidReport)
        inputs.files(configurations.named("detektPlugins"))
        doLast {
            val dependencies = configurations.getByName("detektPlugins")
            val resolution = dependencies.incoming.resolutionResult
            val rootId = resolution.rootComponent.get().id
            check(resolution.allComponents.none { it.id is ProjectComponentIdentifier && it.id != rootId }) {
                "The authoring plugin must resolve from Maven without project substitution."
            }
            val strataModules = resolution.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
                .filter { it.group == "dev.s7a.strata" }.map { it.displayName }.toSet()
            check(strataModules == setOf("dev.s7a.strata:strata-detekt-rules:$strataVersion")) {
                "The plugin must not bring Strata API or runtime modules onto Detekt's plugin classpath: $strataModules"
            }
            val plugin = dependencies.resolvedConfiguration.resolvedArtifacts.single {
                it.moduleVersion.id.group == "dev.s7a.strata" && it.moduleVersion.id.name == "strata-detekt-rules"
            }.file
            ZipFile(plugin).use { archive ->
                val service = checkNotNull(archive.getEntry("META-INF/services/dev.detekt.api.RuleSetProvider"))
                val provider = archive.getInputStream(service).bufferedReader().use { it.readText().trim() }
                check(provider == "dev.s7a.strata.detekt.StrataRuleSetProvider") { "Unexpected provider: $provider" }
                check(archive.getEntry("META-INF/LICENSE-strata") != null) { "Published plugin is missing its license." }
                check(archive.entries().asSequence().none { it.name.startsWith("dev/s7a/strata/quality/") }) {
                    "The plugin must not include Strata's internal source-style rules."
                }
            }
            val errors = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(invalidReport.get().asFile).getElementsByTagName("error")
            val findings = (0 until errors.length).map { (errors.item(it) as Element).getAttribute("source").removePrefix("detekt.") }
            check(findings.groupingBy { it }.eachCount() == expectedRules.associateWith { 1 }) {
                "Expected one finding for each guide rule, received $findings"
            }
            println("Verified Maven plugin loading, zero findings in corrected examples, and one finding for each of ${expectedRules.size} rules.")
        }
    }

tasks.named("check") {
    dependsOn(verifyPublishedAuthoringRules)
}
