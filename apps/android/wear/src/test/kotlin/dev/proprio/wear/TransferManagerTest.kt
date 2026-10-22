package dev.proprio.wear

import dev.proprio.protocol.DataLayer
import dev.proprio.protocol.SessionAck
import dev.proprio.protocol.SessionReader
import dev.proprio.wear.transfer.AckResult
import dev.proprio.wear.transfer.TransferManager
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferManagerTest {
    private val root = createTempDirectory("xfer").toFile()
    private val sender = FakeSender()
    private val nodes = FakeNodes()
    private val tm = TransferManager(File(root, "sessions"), File(root, "outbox"), sender, nodes)

    /** A real finished session directory, produced through the real SessionWriter. */
    private fun makeSession(id: String, complete: Boolean = true) {
        val dir = tm.sessionDir(id)
        val meta = dev.proprio.protocol.Meta(
            sessionId = id, kind = dev.proprio.protocol.SessionKind.GYM, complete = false,
            app = testApp, device = testDevice,
            clockAnchorStart = dev.proprio.protocol.ClockAnchor(1, 2),
            sensors = emptyMap(), startedAtUtc = "2025-01-01T00:00:00Z",
        )
        val w = dev.proprio.protocol.SessionWriter(dir, meta)
        for (i in 0 until 50) {
            w.writeAcc(i * 20_000_000L, 0f, 0f, 9.8f)
            w.writeGyr(i * 20_000_000L, 0f, 0f, 0f)
        }
        if (complete) w.finish(dev.proprio.protocol.ClockAnchor(3, 4), "2025-01-01T00:01:00Z") else w.close()
    }

    @Test
    fun sendsZipOnTheSessionChannelToTheMobileNode() = runTest {
        makeSession("s1")
        val zip = tm.prepare("s1")
        assertEquals(1, tm.pendingCount.value)
        assertEquals(listOf("s1"), tm.sendPending())
        val (node, path, file) = sender.sends.single()
        assertEquals("phone-1", node)
        assertEquals(DataLayer.channelPath("s1"), path)
        assertEquals(zip, file)
        assertEquals(50, SessionReader.readZip(zip).acc.size)
        assertTrue("zip kept until acked", zip.exists())
    }

    @Test
    fun matchingOkAckDeletesZipAndRawDirectory() {
        makeSession("s2")
        val zip = tm.prepare("s2")
        val ack = SessionAck("s2", TransferManager.sha256(zip), ok = true)
        assertEquals(AckResult.DELETED, tm.onAck(ack.copy(sha256 = ack.sha256.uppercase())))
        assertFalse(zip.exists())
        assertFalse(tm.sessionDir("s2").exists())
        assertEquals(0, tm.pendingCount.value)
    }

    @Test
    fun hashMismatchKeepsZipAndRetrySendsAgain() = runTest {
        makeSession("s3")
        val zip = tm.prepare("s3")
        assertEquals(AckResult.KEPT_RETRY, tm.onAck(SessionAck("s3", "0".repeat(64), ok = true)))
        assertTrue(zip.exists())
        assertTrue(tm.sessionDir("s3").exists())
        assertEquals(1, tm.pendingCount.value)
        tm.sendPending()
        tm.sendPending()
        assertEquals(2, sender.sends.size) // each retry resends the kept zip
    }

    @Test
    fun okFalseAckKeepsEvenWithMatchingHash() {
        makeSession("s4")
        val zip = tm.prepare("s4")
        assertEquals(AckResult.KEPT_RETRY, tm.onAck(SessionAck("s4", TransferManager.sha256(zip), ok = false)))
        assertTrue(zip.exists())
    }

    @Test
    fun ackForUnknownSessionIsIgnored() {
        assertEquals(AckResult.UNKNOWN, tm.onAck(SessionAck("nope", "x", ok = true)))
    }

    @Test
    fun noPhoneNodeLeavesTransfersPending() = runTest {
        makeSession("s5")
        tm.prepare("s5")
        nodes.ids = emptyList()
        assertEquals(emptyList<String>(), tm.sendPending())
        assertEquals(1, tm.pendingCount.value)
        nodes.ids = listOf("phone-2") // capability change: phone appears
        assertEquals(listOf("s5"), tm.sendPending())
        assertEquals("phone-2", sender.sends.single().first)
    }

    @Test
    fun failedChannelSendKeepsPendingAndContinues() = runTest {
        makeSession("s6")
        tm.prepare("s6")
        sender.fail = true
        assertEquals(emptyList<String>(), tm.sendPending())
        assertEquals(1, tm.pendingCount.value)
        assertFalse(tm.sending.value)
    }

    @Test
    fun interruptedSessionIsRecoveredAsIncompleteZip() {
        makeSession("crashed", complete = false)
        makeSession("done")
        tm.prepare("done")
        assertEquals(listOf("crashed"), tm.recoverOrphans())
        val read = SessionReader.readZip(tm.zipFile("crashed"))
        assertFalse(read.meta.complete)
        assertEquals(50, read.acc.size)
        assertEquals(listOf("crashed", "done"), tm.pendingSessionIds())
        assertEquals(emptyList<String>(), tm.recoverOrphans())
    }
}
