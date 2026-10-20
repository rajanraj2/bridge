import Foundation
import BridgeDomain
#if canImport(Security)
import Security
#endif

public enum PairingError: Error, Equatable {
    case challengeExpired
    case invalidChallenge
    case sasMismatch
    case userRejected
}

public struct PairingChallengePayload: Codable, Equatable, Sendable {
    public let pairingId: String
    public let deviceId: String
    public let displayName: String
    public let publicKeyBase64: String
    public let challengeBase64: String
    public let port: Int
    public let expiresAtUnixMs: Int64

    public init(pairingId: String, deviceId: String, displayName: String, publicKey: Data, challenge: Data, port: Int, expiresAtUnixMs: Int64) {
        self.pairingId = pairingId
        self.deviceId = deviceId
        self.displayName = displayName
        self.publicKeyBase64 = publicKey.base64EncodedString()
        self.challengeBase64 = challenge.base64EncodedString()
        self.port = port
        self.expiresAtUnixMs = expiresAtUnixMs
    }

    public var publicKey: Data? {
        Data(base64Encoded: publicKeyBase64)
    }

    public var challenge: Data? {
        Data(base64Encoded: challengeBase64)
    }

    public var isExpired: Bool {
        Int64(Date().timeIntervalSince1970 * 1000) > expiresAtUnixMs
    }

    public func toJsonString() throws -> String {
        let encoder = JSONEncoder()
        let data = try encoder.encode(self)
        return String(data: data, encoding: .utf8) ?? ""
    }

    public static func fromJsonString(_ json: String) throws -> PairingChallengePayload {
        guard let data = json.data(using: .utf8) else {
            throw PairingError.invalidChallenge
        }
        let decoder = JSONDecoder()
        return try decoder.decode(PairingChallengePayload.self, from: data)
    }
}

public final class PairingCoordinator: @unchecked Sendable {
    private let lock = NSLock()
    private var activeChallenges: [String: PairingChallengePayload] = [:]
    private let store: TrustedPeerStorage

    public init(store: TrustedPeerStorage) {
        self.store = store
    }

    public func createChallenge(
        deviceId: String,
        displayName: String,
        publicKey: Data,
        port: Int = 54884,
        ttlSeconds: Int = 120
    ) -> PairingChallengePayload {
        lock.lock()
        defer { lock.unlock() }

        let pairingId = UUID().uuidString.lowercased()
        var challengeBytes = Data(count: 32)
        #if canImport(Security)
        _ = challengeBytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        #else
        for i in 0..<32 {
            challengeBytes[i] = UInt8.random(in: 0...255)
        }
        #endif

        let expiresAt = Int64((Date().timeIntervalSince1970 + Double(ttlSeconds)) * 1000)
        let payload = PairingChallengePayload(
            pairingId: pairingId,
            deviceId: deviceId,
            displayName: displayName,
            publicKey: publicKey,
            challenge: challengeBytes,
            port: port,
            expiresAtUnixMs: expiresAt
        )

        activeChallenges[pairingId] = payload
        return payload
    }

    public func computeSAS(localIdentity: Data, peerIdentity: Data, challenge: Data) -> String {
        return IdentityManager.computeSAS(localIdentity: localIdentity, peerIdentity: peerIdentity, challenge: challenge)
    }

    public func completePairing(
        pairingId: String,
        peerDeviceId: String,
        peerDisplayName: String,
        peerPublicKey: Data,
        userConfirmed: Bool
    ) throws -> TrustedPeer {
        lock.lock()
        defer { lock.unlock() }

        guard userConfirmed else {
            activeChallenges.removeValue(forKey: pairingId)
            throw PairingError.userRejected
        }

        guard let challenge = activeChallenges.removeValue(forKey: pairingId) else {
            throw PairingError.invalidChallenge
        }

        if challenge.isExpired {
            throw PairingError.challengeExpired
        }

        let trustedPeer = TrustedPeer(
            deviceId: peerDeviceId,
            displayName: peerDisplayName,
            publicKey: peerPublicKey
        )

        store.savePeer(trustedPeer)
        return trustedPeer
    }

    public func revokePeer(deviceId: String) -> Bool {
        return store.revokePeer(deviceId: deviceId)
    }
}
