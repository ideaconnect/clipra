package tech.idct.clipra.monetization.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.monetization.BuildConfig
import tech.idct.clipra.monetization.Entitlement
import tech.idct.clipra.monetization.ads.AdConsent
import tech.idct.clipra.monetization.ads.AdsGateway
import tech.idct.clipra.monetization.ads.ConsentGateway
import tech.idct.clipra.monetization.ads.ExportAds
import tech.idct.clipra.monetization.ads.NextGenAdsGateway
import tech.idct.clipra.monetization.ads.UmpConsentGateway
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

    @Binds
    abstract fun consentGateway(gateway: UmpConsentGateway): ConsentGateway

    @Binds
    abstract fun adsGateway(gateway: NextGenAdsGateway): AdsGateway

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

        @Provides
        @Singleton
        fun adConsent(gateway: ConsentGateway, entitlement: Entitlement): AdConsent =
            AdConsent(gateway, entitlement)

        @Provides
        @Singleton
        fun exportAds(
            @ApplicationContext context: Context,
            ads: AdsGateway,
            consent: AdConsent,
            entitlement: Entitlement,
            @ApplicationScope scope: CoroutineScope,
        ): ExportAds {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            return ExportAds(
                ads = ads,
                consent = consent,
                entitlement = entitlement,
                online = {
                    connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                },
                clock = SystemClock::elapsedRealtime,
                scope = scope,
            )
        }
    }
}
