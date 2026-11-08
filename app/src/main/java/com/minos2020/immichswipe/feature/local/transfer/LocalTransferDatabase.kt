package com.minos2020.immichswipe.feature.local.transfer

import android.content.Context
import androidx.room.*
import com.google.gson.Gson
import com.minos2020.immichswipe.feature.local.LocalMedia
import kotlinx.coroutines.flow.Flow
import java.io.File

object TransferState {
    const val QUEUED = "queued"
    const val UPLOADING = "uploading"
    const val CLEANUP = "cleanup"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val DONE = "done"
    val active = setOf(QUEUED, UPLOADING, CLEANUP)
}

@Entity(tableName = "transfers")
data class LocalTransferTask(
    @PrimaryKey val key: String,
    val generation: String,
    val account: String,
    val photoJson: String,
    val albumId: String,
    val deleteOnly: Boolean = false,
    val state: String = TransferState.QUEUED,
    val attempts: Int = 0
) {
    fun photo(): LocalMedia = Gson().fromJson(photoJson, LocalMedia::class.java)
}

@Dao
interface LocalTransferDao {
    @Query("SELECT * FROM transfers") fun observe(): Flow<List<LocalTransferTask>>
    @Query("SELECT * FROM transfers WHERE `key` = :key") suspend fun get(key: String): LocalTransferTask?
    @Query("SELECT * FROM transfers WHERE account = :account AND state IN ('queued','uploading','cleanup')")
    suspend fun pending(account: String): List<LocalTransferTask>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(task: LocalTransferTask)
    @Query("UPDATE transfers SET state = :state, attempts = :attempts WHERE `key` = :key AND generation = :generation AND state IN ('queued','uploading','cleanup')")
    suspend fun update(key: String, generation: String, state: String, attempts: Int): Int
    @Query("UPDATE transfers SET state = 'cancelled' WHERE account = :account AND state IN ('queued','uploading','cleanup','failed')")
    suspend fun cancel(account: String)
    @Query("UPDATE transfers SET state = 'queued', attempts = 0 WHERE account = :account AND state = 'failed'")
    suspend fun retry(account: String)
    @Query("SELECT * FROM transfers WHERE state = 'done'") suspend fun done(): List<LocalTransferTask>
    @Query("DELETE FROM transfers WHERE state = 'done' AND `key` IN (:keys)")
    suspend fun prune(keys: List<String>)
}

// Device-local work must never be restored onto another phone by Android backup.
@Database(entities = [LocalTransferTask::class], version = 1, exportSchema = false)
abstract class LocalTransferDatabase : RoomDatabase() {
    abstract fun tasks(): LocalTransferDao
    companion object {
        @Volatile private var instance: LocalTransferDatabase? = null
        fun get(context: Context): LocalTransferDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LocalTransferDatabase::class.java,
                File(context.noBackupFilesDir, "local-transfers.db").absolutePath).build().also { instance = it }
        }
    }
}
