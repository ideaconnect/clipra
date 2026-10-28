package tech.idct.clipra.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Screenshot tests for Compose: Roborazzi on Robolectric's native graphics, run as JVM unit
 * tests. Reference images live in `src/test/screenshots`; `recordRoborazziDebug` writes them and
 * `verifyRoborazziDebug` compares against them. Apply after the Compose convention.
 *
 * Tests run Robolectric at SDK 34 (`@Config(sdk = [34])`): SDK 35 and later need a Java 21 test
 * JVM, and the build uses JDK 17.
 */
class AndroidScreenshotsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.github.takahirom.roborazzi")
            extensions.findByType(ApplicationExtension::class.java)?.testOptions?.unitTests?.let {
                it.isIncludeAndroidResources = true
            }
            extensions.findByType(LibraryExtension::class.java)?.testOptions?.unitTests?.let {
                it.isIncludeAndroidResources = true
            }
            extensions.configure<RoborazziExtension> {
                outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
            }
            tasks.withType<Test>().configureEach {
                systemProperty("robolectric.graphicsMode", "NATIVE")
                systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
            dependencies {
                add("testImplementation", platform(libs.library("androidx-compose-bom")))
                add("testImplementation", libs.library("robolectric"))
                add("testImplementation", libs.library("roborazzi"))
                add("testImplementation", libs.library("roborazzi-compose"))
                add("testImplementation", libs.library("androidx-compose-ui-test-junit4"))
                add("testImplementation", libs.library("androidx-test-ext-junit"))
                add("debugImplementation", libs.library("androidx-compose-ui-test-manifest"))
            }
        }
    }
}
