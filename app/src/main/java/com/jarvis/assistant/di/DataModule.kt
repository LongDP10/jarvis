package com.jarvis.assistant.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.jarvis.assistant.data.db.CommandLogDao
import com.jarvis.assistant.data.db.ConversationDao
import com.jarvis.assistant.data.db.JarvisDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "jarvis_settings",
)

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): JarvisDatabase =
        Room.databaseBuilder(context, JarvisDatabase::class.java, JarvisDatabase.NAME)
            // Conversation history is a convenience, not a record of account. If a
            // future schema change cannot be migrated cleanly, losing old chats is
            // a far better outcome than refusing to launch.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideConversationDao(db: JarvisDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideCommandLogDao(db: JarvisDatabase): CommandLogDao = db.commandLogDao()
}
