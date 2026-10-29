package tech.idct.clipra.core.models.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tech.idct.clipra.core.models.ModelStore
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ModelsModule {
    /** Models live in app-specific storage, removed with the app. */
    @Provides
    @Singleton
    fun modelStore(@ApplicationContext context: Context): ModelStore =
        ModelStore(File(context.filesDir, "models"))
}
