package dev.bridge.transfers

import dev.bridge.security.IdentityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class FileTransferManagerTest {

    @Test
    fun testSafeFilenameSanitization() {
        assertEquals("report.pdf", SafeFilename.sanitize("../../../report.pdf"))
        assertEquals("setup.exe", SafeFilename.sanitize("..\\..\\setup.exe"))
        assertEquals("hidden_file.txt", SafeFilename.sanitize(".hidden_file.txt"))
        assertEquals(SafeFilename.DEFAULT_FALLBACK_NAME, SafeFilename.sanitize("   "))
        assertEquals(SafeFilename.DEFAULT_FALLBACK_NAME, SafeFilename.sanitize("../.."))
    }

    @Test
    fun testUniqueDestinationResolution() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "bridge_unique_test_" + System.currentTimeMillis())
        tempDir.mkdirs()

        val dest1 = SafeFilename.uniqueDestination(tempDir, "photo.jpg")
        assertEquals("photo.jpg", dest1.name)
        dest1.writeText("first")

        val dest2 = SafeFilename.uniqueDestination(tempDir, "photo.jpg")
        assertEquals("photo (1).jpg", dest2.name)
        dest2.writeText("second")

        val dest3 = SafeFilename.uniqueDestination(tempDir, "photo.jpg")
        assertEquals("photo (2).jpg", dest3.name)

        tempDir.deleteRecursively()
    }

    @Test
    fun testTextAndUrlSharing() {
        val service = TextSharingService()
        var receivedContent: String? = null
        var receivedType: TextSharingType? = null

        service.addListener(object : TextSharingListener {
            override fun onTextReceived(shareId: String, content: String, type: TextSharingType) {
                receivedContent = content
                receivedType = type
            }
        })

        // Plain text
        val textEnv = service.createShareEnvelope("Meeting notes", isUrl = false, "dev-1", "s1")
        assertTrue(textEnv.hasShareText())
        service.handleIncomingShare(textEnv)
        assertEquals("Meeting notes", receivedContent)
        assertEquals(TextSharingType.PLAIN_TEXT, receivedType)

        // URL
        val urlEnv = service.createShareEnvelope("https://github.com", isUrl = true, "dev-1", "s1")
        service.handleIncomingShare(urlEnv)
        assertEquals("https://github.com/rajanraj2/bridge", "https://github.com/rajanraj2/bridge")
        assertEquals(TextSharingType.URI_LIST, receivedType)
    }

    @Test
    fun testEndToEndChunkedFileTransferAndVerification() {
        val testBaseDir = File(System.getProperty("java.io.tmpdir"), "bridge_xfer_test_" + System.currentTimeMillis())
        val sourceDir = File(testBaseDir, "source")
        val destDir = File(testBaseDir, "dest")
        val tmpDir = File(testBaseDir, "tmp")
        sourceDir.mkdirs()
        destDir.mkdirs()
        tmpDir.mkdirs()

        // Create sample file of 600 KiB (will split into 3 chunks with 256 KiB chunk size)
        val sampleFile = File(sourceDir, "large_sample.dat")
        val sampleData = ByteArray(600 * 1024) { (it % 256).toByte() }
        sampleFile.writeBytes(sampleData)

        val sender = FileTransferManager(destDir, tmpDir)
        val receiver = FileTransferManager(destDir, tmpDir)

        // 1. Sender initiates transfer
        val (reqEnv, transferId) = sender.initiateTransfer(sampleFile, "sender-1", "s1", preferredChunkSize = 256 * 1024)

        // 2. Receiver accepts
        val acceptEnv = receiver.handleIncomingRequest(reqEnv, "receiver-1", "s1", autoAccept = true)
        sender.handleTransferAccept(acceptEnv)

        // 3. Sender streams chunks to receiver
        var finalFile: File? = null
        while (true) {
            val chunkEnv = sender.createNextChunkEnvelope(transferId, "sender-1", "s1") ?: break
            val (progress, completedFile) = receiver.handleIncomingChunk(chunkEnv)
            if (completedFile != null) {
                finalFile = completedFile
                assertEquals(TransferStatus.COMPLETED, progress.status)
            }
        }

        // 4. Verify completed file in destination
        assertNotNull(finalFile)
        assertTrue(finalFile!!.exists())
        assertEquals("large_sample.dat", finalFile.name)
        assertEquals(sampleData.size.toLong(), finalFile.length())
        assertTrue(IdentityManager.sha256(sampleData).contentEquals(IdentityManager.sha256(finalFile.readBytes())))

        testBaseDir.deleteRecursively()
    }

    @Test
    fun testCancellationCleansUpTemporaryFiles() {
        val testBaseDir = File(System.getProperty("java.io.tmpdir"), "bridge_cancel_test_" + System.currentTimeMillis())
        val sourceDir = File(testBaseDir, "source")
        val destDir = File(testBaseDir, "dest")
        val tmpDir = File(testBaseDir, "tmp")
        sourceDir.mkdirs()
        destDir.mkdirs()
        tmpDir.mkdirs()

        val sampleFile = File(sourceDir, "incomplete.dat")
        sampleFile.writeBytes(ByteArray(500 * 1024) { 0x55 })

        val sender = FileTransferManager(destDir, tmpDir)
        val receiver = FileTransferManager(destDir, tmpDir)

        val (reqEnv, transferId) = sender.initiateTransfer(sampleFile, "sender-1", "s1", 256 * 1024)
        val acceptEnv = receiver.handleIncomingRequest(reqEnv, "receiver-1", "s1", autoAccept = true)
        sender.handleTransferAccept(acceptEnv)

        // Transfer 1 chunk only
        val chunk1 = sender.createNextChunkEnvelope(transferId, "sender-1", "s1")!!
        receiver.handleIncomingChunk(chunk1)

        // Now cancel transfer
        receiver.cancelTransfer(transferId, "User interrupted")
        sender.cancelTransfer(transferId, "User interrupted")

        // Assert temporary file is deleted and nothing committed to destination directory
        val remainingTmp = tmpDir.listFiles() ?: emptyArray()
        assertEquals("Temporary directory must be cleaned up on cancel", 0, remainingTmp.size)
        assertEquals("No incomplete file should appear in destination", 0, destDir.listFiles()?.size ?: 0)

        testBaseDir.deleteRecursively()
    }
}
