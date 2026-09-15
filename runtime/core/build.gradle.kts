kotlin {
    sourceSets {
        commonTest.dependencies { implementation(libs.kotlin.test) }
        commonMain.dependencies {
            api(project(":api"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}
