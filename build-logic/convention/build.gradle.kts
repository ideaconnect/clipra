import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "tech.idct.clipra.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)
    compileOnly(libs.roborazzi.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "clipra.android.application"
            implementationClass = "tech.idct.clipra.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "clipra.android.library"
            implementationClass = "tech.idct.clipra.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "clipra.android.compose"
            implementationClass = "tech.idct.clipra.buildlogic.AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "clipra.android.feature"
            implementationClass = "tech.idct.clipra.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("androidRoom") {
            id = "clipra.android.room"
            implementationClass = "tech.idct.clipra.buildlogic.AndroidRoomConventionPlugin"
        }
        register("androidScreenshots") {
            id = "clipra.android.screenshots"
            implementationClass = "tech.idct.clipra.buildlogic.AndroidScreenshotsConventionPlugin"
        }
        register("hilt") {
            id = "clipra.hilt"
            implementationClass = "tech.idct.clipra.buildlogic.HiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "clipra.jvm.library"
            implementationClass = "tech.idct.clipra.buildlogic.JvmLibraryConventionPlugin"
        }
    }
}
