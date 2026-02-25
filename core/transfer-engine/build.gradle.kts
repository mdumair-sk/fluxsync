plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "11"
            }
        }
    }
    jvm {
        compilations.all {
            kotlinOptions {
                jvmTarget = "11"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(projects.core.protocol)
            implementation(projects.core.resumability)
            implementation(projects.core.security)
        }
        androidMain {
            kotlin.srcDir("src/jvmMain/kotlin")
            kotlin.exclude("**/*.jvm.kt", "**/DebugLog.kt")
            dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
        }
        jvmTest.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
        }
    }
}

android {
    namespace = "com.fluxsync.core.transfer"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
