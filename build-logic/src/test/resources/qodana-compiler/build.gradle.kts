import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.io.File
import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
plugins {
    idea
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.jmh) apply false
}
extensions.configure<IdeaModel> { project.setLanguageLevel(JavaVersion.VERSION_25) }
val completeIdeaModelActive = true
val minecraftTargetByProjectPath = mapOf(":variant-one" to true, ":variant-two" to true)
__INVENTORY__
subprojects {
    if (name in setOf("shared-kmp", "common-support")) {
        apply(plugin = "org.jetbrains.kotlin.multiplatform")
    } else {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(17)) }
    }
    apply(plugin = "idea")
    if (name in setOf("shared-kmp", "common-support")) {
__MULTIPLATFORM_CALLBACK__
    }
    repositories { mavenCentral() }
    plugins.withId("me.champeau.jmh") {
__JMH_CALLBACK__
    }
__AUXILIARY_CALLBACK__
}
