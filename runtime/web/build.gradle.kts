kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":runtime:core")) }
        jsMain.dependencies { implementation(libs.kotlinx.browser) }
        jsTest.dependencies { implementation(libs.kotlin.test) }
    }
}
