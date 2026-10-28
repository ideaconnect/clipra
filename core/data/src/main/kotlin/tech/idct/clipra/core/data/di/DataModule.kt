package tech.idct.clipra.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import tech.idct.clipra.core.data.db.ClipraDatabase
import tech.idct.clipra.core.data.db.ProjectIndexDao
import tech.idct.clipra.core.data.project.ProjectFiles
import tech.idct.clipra.core.data.project.WallClock
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ClipraDatabase =
        Room.databaseBuilder(context, ClipraDatabase::class.java, "clipra.db").build()

    @Provides
    fun projectIndexDao(database: ClipraDatabase): ProjectIndexDao = database.projectIndexDao()

    /** Project documents live in app storage, never in a cache: they are the user's work. */
    @Provides
    @Singleton
    fun projectFiles(@ApplicationContext context: Context): ProjectFiles =
        ProjectFiles(File(context.filesDir, "projects"))

    @Provides
    fun wallClock(): WallClock = WallClock(System::currentTimeMillis)

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
