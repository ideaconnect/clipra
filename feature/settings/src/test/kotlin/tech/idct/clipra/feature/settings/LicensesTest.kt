package tech.idct.clipra.feature.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LicensesTest {
    @Test
    fun everyListedLicenseTextShipsWithTheApp() {
        val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
        OpenSourceComponents.mapNotNull { it.file }.distinct().forEach { file ->
            val text = assets.open(file).bufferedReader().use { it.readText() }
            assertTrue(file, text.length > 500)
        }
    }

    @Test
    fun paragraphsAreJoinedForThePhone() {
        val license = "MIT License\n\nPermission is hereby granted,\n  free of charge.\n\n\n" +
            "THE SOFTWARE\nIS PROVIDED"
        assertEquals(
            "MIT License\n\nPermission is hereby granted, free of charge.\n\n" +
                "THE SOFTWARE IS PROVIDED",
            reflow(license),
        )
    }
}
