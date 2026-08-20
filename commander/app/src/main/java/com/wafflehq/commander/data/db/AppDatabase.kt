package com.wafflehq.commander.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "command_history")
data class CommandHistoryEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val label: String,
    val pathName: String,
    val createdAt: Long,
)

@Dao
interface CommandHistoryDao {
    @Insert
    suspend fun insert(entity: CommandHistoryEntity)

    @Query("SELECT * FROM command_history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CommandHistoryEntity>>
}

@Database(entities = [CommandHistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun commandHistoryDao(): CommandHistoryDao
}
