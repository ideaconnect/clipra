package tech.idct.clipra

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.feature.export.ExportService
import tech.idct.clipra.monetization.ads.AdConsent
import tech.idct.clipra.navigation.ClipraNavHost
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Counts taps on the export notification, so each one opens the export screens. */
    private val openExport = mutableIntStateOf(0)

    @Inject
    lateinit var adConsent: AdConsent

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            ClipraTheme {
                ClipraNavHost(
                    openExport = openExport.intValue,
                    // Ad consent is asked once the user is past onboarding (#62).
                    onProjectsShown = {
                        lifecycleScope.launch { adConsent.gather(this@MainActivity) }
                    },
                )
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
