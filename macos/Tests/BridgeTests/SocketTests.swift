import XCTest
import Foundation
@testable import BridgeDomain
@testable import BridgeProtocol
@testable import BridgeNetwork

private final class MockSocketListener: SocketEventListener, @unchecked Sendable {
    var isConnected = false
    var connectedAddress: String?
    var receivedEnvelopes: [Bridge_Envelope] = []
    let expectation: XCTestExpectation

    init(expectation: XCTestExpectation) {
        self.expectation = expectation
    }

    func onConnected(remoteAddress: String) {
        self.isConnected = true
        self.connectedAddress = remoteAddress
    }

    func onEnvelopeReceived(envelope: Bridge_Envelope) {
        self.receivedEnvelopes.append(envelope)
        expectation.fulfill()
    }

    func onDisconnected(reason: String) {
        self.isConnected = false
    }
}

final class SocketTests: XCTestCase {

    func testLocalSocketCommunication() throws {
        let testPort = 54889
        let serverExpectation = expectation(description: "Server receives client envelope")
        let clientExpectation = expectation(description: "Client receives server envelope")

        let serverListener = MockSocketListener(expectation: serverExpectation)
        let clientListener = MockSocketListener(expectation: clientExpectation)

        let server = BridgeSocketServer(port: testPort)
        server.listener = serverListener
        try server.start()
        defer { server.stop() }

        let client = BridgeSocketClient(listener: clientListener)
        defer { client.disconnect() }

        // Give server thread a moment to bind
        Thread.sleep(forTimeInterval: 0.1)

        try client.connect(host: "127.0.0.1", port: testPort)
        Thread.sleep(forTimeInterval: 0.1)

        // 1. Client sends Hello envelope
        var clientEnv = Bridge_Envelope()
        clientEnv.messageID = "msg-client-001"
        clientEnv.sessionID = "sess-socket"
        var hello = Bridge_Hello()
        hello.deviceID = "client-dev"
        clientEnv.hello = hello

        let sent = client.send(envelope: clientEnv)
        XCTAssertTrue(sent, "Client must send envelope successfully")

        wait(for: [serverExpectation], timeout: 3.0)
        XCTAssertEqual(serverListener.receivedEnvelopes.count, 1)
        XCTAssertEqual(serverListener.receivedEnvelopes.first?.messageID, "msg-client-001")

        // 2. Server sends Heartbeat response
        var serverEnv = Bridge_Envelope()
        serverEnv.messageID = "msg-server-hb"
        var hb = Bridge_Heartbeat()
        hb.sentAtUnixMs = 12345
        serverEnv.heartbeat = hb

        let serverSent = server.send(envelope: serverEnv)
        XCTAssertTrue(serverSent, "Server must send envelope successfully")

        wait(for: [clientExpectation], timeout: 3.0)
        XCTAssertEqual(clientListener.receivedEnvelopes.count, 1)
        XCTAssertEqual(clientListener.receivedEnvelopes.first?.messageID, "msg-server-hb")
    }

    func testLocalIpAddressDiscovery() {
        let ips = BridgeSocketServer.getLocalIpAddresses()
        XCTAssertFalse(ips.isEmpty, "Device must have at least one local or loopback IPv4 address")
    }
}
