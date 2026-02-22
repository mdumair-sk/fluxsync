plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation(projects.core.protocol)
    implementation(projects.core.transferEngine)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jmdns)
}
