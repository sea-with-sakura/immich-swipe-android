package com.minos2020.immichswipe.feature.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.minos2020.immichswipe.feature.local.transfer.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalTransferDatabaseTest {
    @Test fun `cancelled and replaced tasks reject stale worker completion`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LocalTransferDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.tasks()
            val task = LocalTransferTask("photo", "original", "account", "{}", "album")
            dao.put(task); dao.cancel("account")
            assertEquals(0, dao.update("photo", "original", TransferState.DONE, 0))
            assertEquals(TransferState.CANCELLED, dao.get("photo")!!.state)
            dao.put(task.copy(generation = "replacement"))
            assertEquals(0, dao.update("photo", "original", TransferState.DONE, 0))
            assertEquals(TransferState.QUEUED, dao.get("photo")!!.state)
        } finally { db.close() }
    }
    @Test fun `pending work survives database close and reopen`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.noBackupFilesDir, "queue-persistence-test.db")
        fun open() = Room.databaseBuilder(context, LocalTransferDatabase::class.java, path.absolutePath).allowMainThreadQueries().build()
        val task = LocalTransferTask("photo", "generation", "account", "{}", "album", state = TransferState.UPLOADING)
        open().let { db -> db.tasks().put(task); db.close() }
        open().let { db ->
            try {
                assertEquals(listOf(task), db.tasks().pending("account"))
                assertTrue(db.tasks().pending("another-account").isEmpty())
            } finally { db.close(); context.deleteDatabase(path.absolutePath) }
        }
    }
}
