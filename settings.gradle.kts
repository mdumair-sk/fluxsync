rootProject.name = "fluxsync"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
    }
}

include(":core:protocol")
include(":core:resumability")
include(":core:transfer-engine")
include(":core:security")

// Android app/modules
include(":app")
include(":android:data:network")
include(":android:data:storage")
include(":android:service")

// Add desktop modules
include(":desktop:app")
include(":desktop:data:adb")
include(":desktop:data:network")
include(":desktop:data:storage")
