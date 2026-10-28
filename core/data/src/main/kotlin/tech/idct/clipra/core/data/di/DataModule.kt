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
import tech.idct.clipra.core.data.db.ClipraDatabase
import tech.idct.clipra.core.data.db.ProjectIndexDao
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
}
