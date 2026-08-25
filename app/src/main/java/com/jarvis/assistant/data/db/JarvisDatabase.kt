package com.jarvis.assistant.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        CommandLogEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun commandLogDao(): CommandLogDao

    companion object {
        const val NAME = "jarvis.db"
    }
}
