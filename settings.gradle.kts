pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Clipra"

// Modules as in docs/architecture.md, section 2.
include(":app")
include(":core:designsystem", ":core:model", ":core:data", ":core:engine", ":core:audio")
include(":ml:whisper", ":ml:voice", ":ml:vision", ":ml:translate")
include(
    ":feature:home",
    ":feature:editor",
    ":feature:effects",
    ":feature:text",
    ":feature:captions",
    ":feature:audio",
    ":feature:export",
    ":feature:models",
)
include(":monetization")
