package tech.idct.clipra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.navigation.ClipraNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ClipraTheme {
                ClipraNavHost()
            }
        }
    }
}
