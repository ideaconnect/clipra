package tech.idct.clipra.monetization.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.monetization.BuildConfig
import tech.idct.clipra.monetization.Entitlement
import tech.idct.clipra.monetization.billing.BillingGateway
import tech.idct.clipra.monetization.billing.PlayBillingGateway
import tech.idct.clipra.monetization.billing.ProStore
import tech.idct.clipra.monetization.billing.PurchaseVerifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MonetizationModule {
    @Binds
    abstract fun billingGateway(gateway: PlayBillingGateway): BillingGateway

    @Binds
    abstract fun entitlement(store: ProStore): Entitlement

    companion object {
        @Provides
        @Singleton
        fun proStore(
            gateway: BillingGateway,
            dataStore: DataStore<Preferences>,
            @ApplicationScope scope: CoroutineScope,
        ): ProStore = ProStore(
            gateway = gateway,
            verifier = PurchaseVerifier(
                base64Key = PurchaseVerifier.reveal(BuildConfig.PLAY_LICENSE_KEY),
                trustUnsigned = BuildConfig.DEBUG,
            ),
            dataStore = dataStore,
            scope = scope,
        )
    }
}
