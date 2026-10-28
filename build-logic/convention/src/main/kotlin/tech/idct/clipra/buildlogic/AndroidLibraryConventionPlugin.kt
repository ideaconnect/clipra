package tech.idct.clipra.buildlogic

import com.android.build.api.dsl.LibraryExtension
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
            dependencies {
                add("testImplementation", libs.library("junit"))
            }
        }
    }
}
