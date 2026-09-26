import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink

// The demo and runtime share a short project name but must not share Gradle component coordinates.
group = "dev.s7a.strata.examples"

kotlin {
    js {
        outputModuleName = "strata-web-demos"
        binaries.executable()
        browser {
            commonWebpackConfig {
                outputFileName = "app.js"
            }
        }
    }
    sourceSets {
        jsMain.dependencies {
            implementation(project(":runtime:web"))
            implementation(libs.kotlinx.browser)
        }
        jsTest.dependencies { implementation(libs.kotlin.test) }
    }
}

tasks.named<KotlinJsIrLink>("compileProductionExecutableKotlinJs") {
    // KT-68281 makes DCE supertype metadata order unstable, breaking independent Pages artifact comparison.
    compilerOptions.freeCompilerArgs.add("-Xir-dce=false")
}
