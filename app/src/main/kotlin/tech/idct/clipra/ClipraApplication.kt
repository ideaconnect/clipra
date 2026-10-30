package tech.idct.clipra

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import tech.idct.clipra.monetization.billing.ProStore
import javax.inject.Inject

@HiltAndroidApp
class ClipraApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var proStore: ProStore

    override fun onCreate() {
        super.onCreate()
        // Restores Clipra Pro on every start, and revokes it after a refund.
        proStore.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
