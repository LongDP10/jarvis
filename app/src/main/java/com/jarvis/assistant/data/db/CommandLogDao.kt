package com.jarvis.assistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandLogDao {

    @Insert
    suspend fun insert(entry: CommandLogEntity): Long

    @Query("SELECT * FROM command_log ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CommandLogEntity>>

    @Query("DELETE FROM command_log")
    suspend fun deleteAll()

    /**
     * Keeps the log from growing without bound. The debug console never shows
     * more than a few hundred rows, so anything older is dead weight.
     */
    @Query(
        "DELETE FROM command_log WHERE id NOT IN " +
            "(SELECT id FROM command_log ORDER BY createdAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)
}
