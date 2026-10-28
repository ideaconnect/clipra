package tech.idct.clipra.core.engine.media

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
    @Provides
    @Singleton
    fun mediaCache(@ApplicationContext context: Context): MediaCache =
        MediaCache(File(context.cacheDir, "media"))
}
