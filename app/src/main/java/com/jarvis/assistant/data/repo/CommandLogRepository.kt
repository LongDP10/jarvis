package com.jarvis.assistant.data.repo

import com.jarvis.assistant.data.db.CommandLogDao
import com.jarvis.assistant.data.db.CommandLogEntity
import com.jarvis.assistant.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class LogStage {
    VOICE,
    AI,
    TOOL,
    RESULT,
    TTS,
    ERROR,
}

data class CommandLogEntry(
    val id: Long = 0,
    val turnId: String,
    val stage: LogStage,
    val label: String,
    val detail: String,
    val success: Boolean? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Durable trace of what JARVIS did, powering the debug console.
 *
 * Writes are dropped entirely unless the user has turned the debug log on, so
 * the normal path costs nothing. Nothing written here may contain an API key or
 * a message body the user has not already seen on screen.
 */
@Singleton
class CommandLogRepository @Inject constructor(
    private val dao: CommandLogDao,
    private val settings: SettingsRepository,
) {

    fun newTurnId(): String = UUID.randomUUID().toString()

    fun observeRecent(limit: Int = DEFAULT_LIMIT): Flow<List<CommandLogEntry>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    suspend fun log(
        turnId: String,
        stage: LogStage,
        label: String,
        detail: String = "",
        success: Boolean? = null,
    ) {
        // Failures are always recorded, the debug toggle only controls the
        // verbose trace. Gating everything meant that when a command went wrong
        // the one place built to explain it was empty, which is exactly backwards:
        // nobody turns on debug logging before the bug they have not hit yet.
        val isFailure = stage == LogStage.ERROR || success == false
        if (!isFailure && !settings.current().debugLogEnabled) return
        dao.insert(
            CommandLogEntity(
                turnId = turnId,
                stage = stage.name,
                label = label,
                detail = detail.take(DETAIL_LIMIT),
                success = success,
                createdAt = System.currentTimeMillis(),
            ),
        )
        dao.trimTo(MAX_ROWS)
    }

    suspend fun clear() = dao.deleteAll()

    private fun CommandLogEntity.toDomain() = CommandLogEntry(
        id = id,
        turnId = turnId,
        stage = runCatching { LogStage.valueOf(stage) }.getOrDefault(LogStage.RESULT),
        label = label,
        detail = detail,
        success = success,
        createdAt = createdAt,
    )

    private companion object {
        const val DEFAULT_LIMIT = 300
        const val MAX_ROWS = 1000
        const val DETAIL_LIMIT = 4000
    }
}
