package com.wafflehq.commander.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "dev_context")
data class DevContextEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val value: String,
)

@Dao
interface DevContextDao {
    @Insert
    suspend fun insert(entity: DevContextEntity): Long

    @Update
    suspend fun update(entity: DevContextEntity)

    @Delete
    suspend fun delete(entity: DevContextEntity)

    @Query("SELECT * FROM dev_context ORDER BY name")
    fun observeAll(): Flow<List<DevContextEntity>>

    @Query("SELECT * FROM dev_context WHERE id = :id")
    suspend fun getById(id: Long): DevContextEntity?
}

@Database(entities = [DevContextEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun devContextDao(): DevContextDao
}
