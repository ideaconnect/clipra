package tech.idct.clipra.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> {
                compileSdk = ClipraBuild.COMPILE_SDK
                defaultConfig {
                    minSdk = ClipraBuild.MIN_SDK
                    targetSdk = ClipraBuild.TARGET_SDK
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
