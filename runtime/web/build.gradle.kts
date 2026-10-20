kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":runtime:core")) }
        jsMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(libs.kotlinx.coroutines.core)
        }
        jsTest.dependencies { implementation(libs.kotlin.test) }
    }
}
