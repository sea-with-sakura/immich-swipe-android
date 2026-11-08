package com.minos2020.immichswipe.feature.local

import com.google.gson.Gson
import com.minos2020.immichswipe.data.repository.*
import com.minos2020.immichswipe.feature.local.transfer.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import java.io.IOException

class LocalTransferProcessorTest {
    private val dao = mockk<LocalTransferDao>()
    private val media = mockk<LocalMediaRepository>(relaxed = true)
    private val transfer = mockk<LocalTransferRepository>(relaxed = true)
    private val photo = LocalMedia("content://media/1", "1.jpg", "image/jpeg", 100, 1, 1)
    private var task = LocalTransferTask(photo.key, "gen", "account", Gson().toJson(photo), "album")
    private var validSession = true
    private var permission = true
    private var deleted = 0
    private val receipt = LocalUploadReceipt("remote", "hash", "album")
    init {
        coEvery { dao.pending("account") } answers { listOf(task).filter { it.state in TransferState.active } }
        coEvery { dao.get(photo.key) } answers { task }
        coEvery { dao.update(any(), any(), any(), any()) } answers {
            if (task.generation != secondArg<String>() || task.state !in TransferState.active) 0
            else { task = task.copy(state = thirdArg(), attempts = arg(3)); 1 }
        }
        every { media.receipt("account", photo) } returns receipt
        coEvery { media.unchanged(photo) } returns true
        coEvery { transfer.uploadToAlbum(photo, "album", any()) } returns receipt
    }
    private fun processor() = LocalTransferProcessor(dao, media, transfer, "account", { validSession }, { permission }, { deleted++; true })
    @Test fun `verified upload cleans up without any UI dependency`() = runTest {
        assertFalse(processor().run()); assertEquals(1, deleted); assertEquals(TransferState.DONE, task.state)
        coVerify { transfer.verifyBeforeDeletion(photo, receipt) }
    }
    @Test fun `missing manage media permission defers cleanup with original intact`() = runTest {
        permission = false; processor().run()
        assertEquals(0, deleted); assertEquals(TransferState.CLEANUP, task.state)
        permission = true; processor().run()
        assertEquals(1, deleted); assertEquals(TransferState.DONE, task.state)
    }
    @Test fun `checksum or album verification failure cannot delete original`() = runTest {
        coEvery { transfer.verifyBeforeDeletion(any(), any()) } throws IllegalStateException("checksum mismatch")
        processor().run(); assertEquals(0, deleted); assertEquals(TransferState.FAILED, task.state)
    }
    @Test fun `cancelling during upload prevents later deletion`() = runTest {
        coEvery { transfer.uploadToAlbum(photo, "album", any()) } coAnswers { task = task.copy(state = TransferState.CANCELLED); receipt }
        processor().run(); assertEquals(0, deleted); assertEquals(TransferState.CANCELLED, task.state)
    }
    @Test fun `new task generation cannot be deleted by an older worker`() = runTest {
        coEvery { transfer.uploadToAlbum(photo, "album", any()) } coAnswers { task = task.copy(generation = "new"); receipt }
        processor().run(); assertEquals(0, deleted); assertEquals("new", task.generation)
    }
    @Test fun `account switch after cloud verification retains original`() = runTest {
        coEvery { transfer.verifyBeforeDeletion(any(), any()) } coAnswers { validSession = false }
        processor().run(); assertEquals(0, deleted)
    }
    @Test fun `network errors are retried without removing source`() = runTest {
        coEvery { transfer.uploadToAlbum(any(), any(), any()) } throws IOException("offline")
        assertTrue(processor().run()); assertEquals(0, deleted); assertEquals(TransferState.QUEUED, task.state)
        task = task.copy(attempts = 3)
        assertFalse(processor().run()); assertEquals(TransferState.FAILED, task.state)
    }
    @Test fun `direct local deletion never uploads any bytes`() = runTest {
        task = task.copy(deleteOnly = true)
        processor().run(); assertEquals(1, deleted)
        coVerify(exactly = 0) { transfer.uploadToAlbum(any(), any(), any()) }
    }
    @Test fun `pending intent dispatch is not counted as successful deletion`() = runTest {
        LocalTransferProcessor(dao, media, transfer, "account", { true }, { true }, { false }).run()
        assertEquals(TransferState.CLEANUP, task.state)
    }
    @Test fun `photos queued during an upload drain before the foreground worker exits`() = runTest {
        val second = photo.copy(uri = "content://media/2", name = "2.jpg")
        var other = task.copy(key = second.key, generation = "other", photoJson = Gson().toJson(second))
        var arrived = false
        coEvery { dao.pending("account") } answers { if (arrived) listOf(task, other) else listOf(task) }
        coEvery { dao.get(second.key) } answers { other }
        coEvery { dao.update(second.key, "other", any(), any()) } answers {
            other = other.copy(state = thirdArg(), attempts = arg(3)); 1
        }
        coEvery { transfer.uploadToAlbum(photo, "album", any()) } coAnswers { arrived = true; receipt }
        coEvery { transfer.uploadToAlbum(second, "album", any()) } returns receipt.copy(assetId = "second")
        processor().run()
        assertEquals(2, deleted)
        assertEquals(TransferState.DONE, other.state)
    }

}
