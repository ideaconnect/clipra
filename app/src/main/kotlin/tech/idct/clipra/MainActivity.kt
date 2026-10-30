package tech.idct.clipra

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.idct.clipra.core.data.settings.SettingsRepository
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.adaptive.ProvideWindowLayout
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

    @Inject
    lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            ClipraTheme {
                // The first run starts at onboarding (#73). Read once, so finishing onboarding
                // does not change the start of a graph already shown; DataStore answers in a
                // moment, and the background shows until then.
                var onboarded by rememberSaveable { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) {
                    if (onboarded == null) onboarded = settings.onboarded.first()
                }
                val start = onboarded
                if (start == null) {
                    Box(Modifier.fillMaxSize().background(ClipraTheme.colors.background))
                } else {
                    // Phone or tablet layout by the window's size (#68), switched in place.
                    ProvideWindowLayout {
                        ClipraNavHost(
                            openExport = openExport.intValue,
                            // Ad consent is asked once the user is past onboarding (#62).
                            onProjectsShown = {
                                lifecycleScope.launch { adConsent.gather(this@MainActivity) }
                            },
                            onboarded = start,
                        )
                    }
                }
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
