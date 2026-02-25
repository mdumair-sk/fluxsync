buildscript {
    repositories {
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
        mavenCentral()
        google()
    }
}
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    // Core modules
    implementation(projects.core.protocol)
    implementation(projects.core.transferEngine)
    implementation(projects.core.security)
    implementation(projects.core.resumability)

    // Desktop data modules
    implementation(projects.desktop.data.adb)
    implementation(projects.desktop.data.network)
    implementation(projects.desktop.data.storage)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    // JmDNS (also available via desktop:data:network, explicit here for clarity)
    implementation(libs.jmdns)

    // Compose Multiplatform
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.components.resources)

    // QR generation
    implementation("com.google.zxing:core:3.5.3")
}

compose.desktop {
    application {
        mainClass = "com.fluxsync.desktop.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "FluxSync"
            packageVersion = "1.0.0"

            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "FluxSync"
                upgradeUuid = "8b3e7a2c-4f1d-4e9a-b6c3-2d8f0e5a9b1c"
            }
            macOS {
                iconFile.set(project.file("src/main/resources/icon.icns"))
                bundleID = "com.fluxsync.desktop"
            }
        }
    }
}
