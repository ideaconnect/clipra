package tech.idct.clipra

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import dagger.hilt.android.AndroidEntryPoint
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.feature.export.ExportService
import tech.idct.clipra.navigation.ClipraNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Counts taps on the export notification, so each one opens the export screens. */
    private val openExport = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            ClipraTheme {
                ClipraNavHost(openExport = openExport.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (intent.getStringExtra(ExportService.EXTRA_OPEN) == ExportService.OPEN_EXPORT) {
            openExport.intValue += 1
        }
    }
}
