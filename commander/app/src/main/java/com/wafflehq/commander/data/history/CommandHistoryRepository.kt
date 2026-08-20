package com.wafflehq.commander.data.history

import com.wafflehq.commander.data.db.CommandHistoryDao
import com.wafflehq.commander.data.db.CommandHistoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

enum class CommandKind { AGENT, PATH_COMMAND }

@Singleton
class CommandHistoryRepository @Inject constructor(
    private val dao: CommandHistoryDao,
) {
    val history: Flow<List<CommandHistoryEntity>> = dao.observeAll()

    suspend fun record(id: String, kind: CommandKind, label: String, pathName: String) {
        dao.insert(
            CommandHistoryEntity(
                id = id,
                kind = kind.name,
                label = label,
                pathName = pathName,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }
}
