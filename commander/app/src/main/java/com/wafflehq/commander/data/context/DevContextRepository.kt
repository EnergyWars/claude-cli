package com.wafflehq.commander.data.context

import com.wafflehq.commander.data.db.DevContextDao
import com.wafflehq.commander.data.db.DevContextEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DevContextRepository @Inject constructor(
    private val dao: DevContextDao,
) {
    val contexts: Flow<List<DevContextEntity>> = dao.observeAll()

    suspend fun add(name: String, value: String) = dao.insert(DevContextEntity(name = name, value = value))

    suspend fun update(id: Long, name: String, value: String) = dao.update(DevContextEntity(id = id, name = name, value = value))

    suspend fun delete(entity: DevContextEntity) = dao.delete(entity)

    suspend fun getById(id: Long): DevContextEntity? = dao.getById(id)
}
