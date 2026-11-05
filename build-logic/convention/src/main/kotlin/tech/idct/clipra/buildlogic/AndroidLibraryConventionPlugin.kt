package tech.idct.clipra.buildlogic

import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                compileSdk = ClipraBuild.COMPILE_SDK
                defaultConfig {
                    minSdk = ClipraBuild.MIN_SDK
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = ClipraBuild.java
                    targetCompatibility = ClipraBuild.java
                }
            }
            // A module without src/androidTest gets no test APK. AGP would still build one from
            // the module's own and generated (Hilt) classes, without androidx.test, and
            // connectedDebugAndroidTest would crash on it with ClassNotFoundException (#196).
            // A module that adds instrumented tests also adds androidx.test (see core:engine).
            if (!file("src/androidTest").isDirectory) {
                extensions.configure<LibraryAndroidComponentsExtension> {
                    beforeVariants { variant -> variant.androidTest.enable = false }
                }
            }
            dependencies {
                add("testImplementation", libs.library("junit"))
            }
        }
    }
}
