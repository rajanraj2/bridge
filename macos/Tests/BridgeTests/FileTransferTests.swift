import XCTest
import Foundation
@testable import BridgeDomain
@testable import BridgeProtocol
@testable import BridgeSecurity
@testable import BridgeTransfers

final class FileTransferTests: XCTestCase {

    func testSafeFilenameSanitization() {
        XCTAssertEqual(SafeFilename.sanitize("../../../evil.sh"), "evil.sh")
        XCTAssertEqual(SafeFilename.sanitize("..\\..\\malware.exe"), "malware.exe")
        XCTAssertEqual(SafeFilename.sanitize(".hidden"), "hidden")
        XCTAssertEqual(SafeFilename.sanitize("   "), SafeFilename.defaultFallbackName)
        XCTAssertEqual(SafeFilename.sanitize("../.."), SafeFilename.defaultFallbackName)
    }

    func testUniqueDestinationResolution() throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("bridge_unique_swift_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)

        let dest1 = SafeFilename.uniqueDestinationURL(in: tempDir, filename: "doc.pdf")
        XCTAssertEqual(dest1.lastPathComponent, "doc.pdf")
        try "file1".write(to: dest1, atomically: true, encoding: .utf8)

        let dest2 = SafeFilename.uniqueDestinationURL(in: tempDir, filename: "doc.pdf")
        XCTAssertEqual(dest2.lastPathComponent, "doc (1).pdf")
        try "file2".write(to: dest2, atomically: true, encoding: .utf8)

        let dest3 = SafeFilename.uniqueDestinationURL(in: tempDir, filename: "doc.pdf")
        XCTAssertEqual(dest3.lastPathComponent, "doc (2).pdf")

        try? FileManager.default.removeItem(at: tempDir)
    }

    func testTextAndURLSharing() throws {
        let service = TextSharingService()

        final class TestDelegate: TextSharingDelegate, @unchecked Sendable {
            var receivedContent: String?
            var receivedType: TextSharingType?
            func didReceiveSharedText(shareId: String, content: String, type: TextSharingType) {
                self.receivedContent = content
                self.receivedType = type
            }
        }

        let delegate = TestDelegate()
        service.delegate = delegate

        let textEnv = try service.createShareEnvelope(content: "Shared notes", isURL: false, senderDeviceId: "d1", sessionId: "s1")
        _ = service.handleIncomingShare(textEnv)
        XCTAssertEqual(delegate.receivedContent, "Shared notes")
        XCTAssertEqual(delegate.receivedType, .plainText)

        let urlEnv = try service.createShareEnvelope(content: "https://github.com", isURL: true, senderDeviceId: "d1", sessionId: "s1")
        _ = service.handleIncomingShare(urlEnv)
        XCTAssertEqual(delegate.receivedContent, "https://github.com")
        XCTAssertEqual(delegate.receivedType, .uriList)
    }

    func testEndToEndFileTransferAndVerification() throws {
        let testDir = FileManager.default.temporaryDirectory.appendingPathComponent("bridge_swift_xfer_\(UUID().uuidString)")
        let sourceDir = testDir.appendingPathComponent("source")
        let destDir = testDir.appendingPathComponent("dest")
        let tmpDir = testDir.appendingPathComponent("tmp")
        try FileManager.default.createDirectory(at: sourceDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: destDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)

        // Generate 500 KiB sample data (will split across 2 chunks with 256 KiB chunk size)
        let sampleFile = sourceDir.appendingPathComponent("sample_image.png")
        var sampleData = Data(count: 500 * 1024)
        for i in 0..<sampleData.count {
            sampleData[i] = UInt8(i % 256)
        }
        try sampleData.write(to: sampleFile)

        let sender = FileTransferManager(destinationDirectory: destDir, temporaryDirectory: tmpDir)
        let receiver = FileTransferManager(destinationDirectory: destDir, temporaryDirectory: tmpDir)

        let (reqEnv, transferId) = try sender.initiateTransfer(sourceURL: sampleFile, senderDeviceId: "sender-1", sessionId: "s1", preferredChunkSize: 256 * 1024)
        let acceptEnv = try receiver.handleIncomingRequest(reqEnv, senderDeviceId: "receiver-1", sessionId: "s1", autoAccept: true)
        try sender.handleTransferAccept(acceptEnv)

        var finalCompletedURL: URL?
        while let chunkEnv = try sender.createNextChunkEnvelope(transferId: transferId, senderDeviceId: "sender-1", sessionId: "s1") {
            let (progress, finalURL) = try receiver.handleIncomingChunk(chunkEnv)
            if let url = finalURL {
                finalCompletedURL = url
                XCTAssertEqual(progress.status, .completed)
            }
        }

        XCTAssertNotNil(finalCompletedURL)
        XCTAssertTrue(FileManager.default.fileExists(atPath: finalCompletedURL!.path))
        XCTAssertEqual(finalCompletedURL!.lastPathComponent, "sample_image.png")

        let receivedData = try Data(contentsOf: finalCompletedURL!)
        XCTAssertEqual(receivedData.count, sampleData.count)
        XCTAssertEqual(IdentityManager.sha256(data: receivedData), IdentityManager.sha256(data: sampleData))

        try? FileManager.default.removeItem(at: testDir)
    }

    func testCancellationRemovesTemporaryFile() throws {
        let testDir = FileManager.default.temporaryDirectory.appendingPathComponent("bridge_swift_cancel_\(UUID().uuidString)")
        let sourceDir = testDir.appendingPathComponent("source")
        let destDir = testDir.appendingPathComponent("dest")
        let tmpDir = testDir.appendingPathComponent("tmp")
        try FileManager.default.createDirectory(at: sourceDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: destDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)

        let sampleFile = sourceDir.appendingPathComponent("interrupted.dat")
        try Data(repeating: 0x42, count: 500 * 1024).write(to: sampleFile)

        let sender = FileTransferManager(destinationDirectory: destDir, temporaryDirectory: tmpDir)
        let receiver = FileTransferManager(destinationDirectory: destDir, temporaryDirectory: tmpDir)

        let (reqEnv, transferId) = try sender.initiateTransfer(sourceURL: sampleFile, senderDeviceId: "s1", sessionId: "sess", preferredChunkSize: 256 * 1024)
        let acceptEnv = try receiver.handleIncomingRequest(reqEnv, senderDeviceId: "r1", sessionId: "sess", autoAccept: true)
        try sender.handleTransferAccept(acceptEnv)

        // Stream only first chunk
        let chunk1 = try sender.createNextChunkEnvelope(transferId: transferId, senderDeviceId: "s1", sessionId: "sess")!
        _ = try receiver.handleIncomingChunk(chunk1)

        // Cancel
        receiver.cancelTransfer(transferId: transferId, reason: "Interrupted")
        sender.cancelTransfer(transferId: transferId, reason: "Interrupted")

        let remainingTmp = (try? FileManager.default.contentsOfDirectory(atPath: tmpDir.path)) ?? []
        XCTAssertEqual(remainingTmp.count, 0, "Temporary files must be deleted on cancellation")

        let destFiles = (try? FileManager.default.contentsOfDirectory(atPath: destDir.path)) ?? []
        XCTAssertEqual(destFiles.count, 0, "Destination must not contain incomplete files")

        try? FileManager.default.removeItem(at: testDir)
    }
}
