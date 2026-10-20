import Foundation
import BridgeDomain

public enum FramingError: Error, Equatable {
    case messageTooLarge(actual: Int, maximum: Int)
    case incompleteHeader
    case invalidPayload
}

public struct WireFrame {
    public static let maxControlFrameSize: Int = 1_048_576 // 1 MiB
    public static let headerSize: Int = 4

    /// Encodes payload bytes with a 4-byte big-endian length prefix.
    public static func encode(payload: Data) throws -> Data {
        guard payload.count <= maxControlFrameSize else {
            throw FramingError.messageTooLarge(actual: payload.count, maximum: maxControlFrameSize)
        }

        var header = UInt32(payload.count).bigEndian
        var packet = Data(bytes: &header, count: 4)
        packet.append(payload)
        return packet
    }

    /// Attempts to decode a length-delimited frame from a buffer.
    /// Returns (decodedPayload, bytesConsumed) if a complete frame is present, or nil if incomplete.
    public static func decode(from buffer: Data) throws -> (payload: Data, consumed: Int)? {
        guard buffer.count >= headerSize else {
            return nil
        }

        let lengthBytes = buffer.prefix(4)
        let payloadLength = Int(UInt32(bigEndian: lengthBytes.withUnsafeBytes { $0.load(as: UInt32.self) }))

        guard payloadLength <= maxControlFrameSize else {
            throw FramingError.messageTooLarge(actual: payloadLength, maximum: maxControlFrameSize)
        }

        let totalExpected = headerSize + payloadLength
        guard buffer.count >= totalExpected else {
            return nil
        }

        let payload = buffer.subdata(in: headerSize..<totalExpected)
        return (payload: payload, consumed: totalExpected)
    }
}
