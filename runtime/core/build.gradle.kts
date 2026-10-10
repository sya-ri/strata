kotlin {
    js {
        nodejs {
            testTask {
                // Exhaustive finite matrices and 1,000 retained cycles need more than Mocha's default two seconds.
                useMocha { timeout = "60s" }
            }
        }
    }
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
