package tech.idct.clipra

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Import uses the photo picker and the file picker, so no storage permission is requested, and
 * nothing starts telemetry.
 */
@RunWith(AndroidJUnit4::class)
class PermissionsTest {
    @Test
    fun noStorageOrMediaPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        val storage = requested.filter {
            it.endsWith("READ_EXTERNAL_STORAGE") ||
                it.endsWith("WRITE_EXTERNAL_STORAGE") ||
                it.startsWith("android.permission.READ_MEDIA_")
        }
        assertEquals(emptyList<String>(), storage)
    }

    /** ONNX Runtime's 1DS telemetry starts from its own provider; `ml:voice` removes it. */
    @Test
    fun noTelemetryProviders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val providers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
            .providers.orEmpty()
            .map { it.name }
        assertEquals(emptyList<String>(), providers.filter { "elemetry" in it })
    }
}
