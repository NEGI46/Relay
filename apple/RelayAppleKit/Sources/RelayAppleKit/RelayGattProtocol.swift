import CryptoKit
import Foundation

/// The fixed UUIDs exposed by the Windows Relay BLE bridge.  They are a wire
/// contract shared with `pc-ble-bridge`; changing them needs a protocol bump.
public enum RelayGattContract {
    public static let service = UUID(uuidString: "1f7f7e90-4e0a-4b0b-8fad-1e3c5e3f4a01")!
    public static let identity = UUID(uuidString: "1f7f7e91-4e0a-4b0b-8fad-1e3c5e3f4a01")!
    public static let uplink = UUID(uuidString: "1f7f7e92-4e0a-4b0b-8fad-1e3c5e3f4a01")!
    public static let downlink = UUID(uuidString: "1f7f7e93-4e0a-4b0b-8fad-1e3c5e3f4a01")!
}

public enum RelayGattFrameKind: UInt8, Sendable {
    case start = 1, chunk = 2, commit = 3, result = 4, abort = 5
}

public enum RelayGattProtocolError: Error, Equatable {
    case invalidFrame
    case invalidIdentifier
    case invalidStartMetadata
    case invalidEnvelopeSize
    case invalidSession
}

/// MTU-23-safe frame.  The ten-byte header leaves at most ten bytes of data,
/// matching the Windows peripheral exactly.
public struct RelayGattFrame: Equatable, Sendable {
    public static let maximumValueBytes = 20
    public static let headerBytes = 10
    public static let maximumPayloadBytes = 10
    public static let sessionIdBytes = 4

    public let kind: RelayGattFrameKind
    public let sequence: UInt16
    public let total: UInt16
    public let sessionId: Data
    public let payload: Data

    public init(kind: RelayGattFrameKind, sequence: UInt16, total: UInt16, sessionId: Data, payload: Data) throws {
        guard sessionId.count == Self.sessionIdBytes, payload.count <= Self.maximumPayloadBytes else {
            throw RelayGattProtocolError.invalidFrame
        }
        self.kind = kind
        self.sequence = sequence
        self.total = total
        self.sessionId = sessionId
        self.payload = payload
    }

    public func encoded() -> Data {
        var output = Data([kind.rawValue, UInt8(payload.count)])
        output.append(contentsOf: [UInt8(sequence >> 8), UInt8(sequence & 0xff)])
        output.append(contentsOf: [UInt8(total >> 8), UInt8(total & 0xff)])
        output.append(sessionId)
        output.append(payload)
        return output
    }

    public static func decode(_ data: Data) throws -> RelayGattFrame {
        guard data.count >= headerBytes, data.count <= maximumValueBytes,
              let kind = RelayGattFrameKind(rawValue: data[0]),
              Int(data[1]) <= maximumPayloadBytes,
              data.count == headerBytes + Int(data[1]) else {
            throw RelayGattProtocolError.invalidFrame
        }
        let sequence = UInt16(data[2]) << 8 | UInt16(data[3])
        let total = UInt16(data[4]) << 8 | UInt16(data[5])
        return try RelayGattFrame(
            kind: kind,
            sequence: sequence,
            total: total,
            sessionId: data.subdata(in: 6..<10),
            payload: data.subdata(in: 10..<data.count)
        )
    }

    public static func fragments(kind: RelayGattFrameKind, sessionId: Data, bytes: Data) throws -> [RelayGattFrame] {
        guard bytes.count <= RelayDeliveryLimits.maximumEnvelopeBytes else {
            throw RelayGattProtocolError.invalidEnvelopeSize
        }
        let total = max(1, Int(ceil(Double(bytes.count) / Double(maximumPayloadBytes))))
        guard total <= Int(UInt16.max) else { throw RelayGattProtocolError.invalidFrame }
        return try (0..<total).map { sequence in
            let start = sequence * maximumPayloadBytes
            let end = min(start + maximumPayloadBytes, bytes.count)
            return try RelayGattFrame(
                kind: kind,
                sequence: UInt16(sequence),
                total: UInt16(total),
                sessionId: sessionId,
                payload: bytes.subdata(in: start..<end)
            )
        }
    }
}

public enum RelayDeliveryLimits {
    public static let maximumEnvelopeBytes = 16 * 1024
    public static let maximumCourierDeliveryIdBytes = 256
    public static let maximumCarrierIdBytes = 128
}

/// START metadata is sent before opaque envelope bytes.  It deliberately
/// contains neither a rescue body nor a decryption key.
public struct RelayDeliveryStartMetadata: Equatable, Sendable {
    public let courierDeliveryId: String
    public let carrierId: String

    public init(courierDeliveryId: String, carrierId: String) throws {
        try Self.validate(courierDeliveryId, maximum: RelayDeliveryLimits.maximumCourierDeliveryIdBytes)
        try Self.validate(carrierId, maximum: RelayDeliveryLimits.maximumCarrierIdBytes)
        self.courierDeliveryId = courierDeliveryId
        self.carrierId = carrierId
    }

    public func encoded() -> Data {
        let courier = Data(courierDeliveryId.utf8)
        let carrier = Data(carrierId.utf8)
        var result = Data([1])
        result.appendUInt16BE(UInt16(courier.count))
        result.appendUInt16BE(UInt16(carrier.count))
        result.append(courier)
        result.append(carrier)
        return result
    }

    private static func validate(_ value: String, maximum: Int) throws {
        let bytes = Array(value.utf8)
        guard !bytes.isEmpty, bytes.count <= maximum,
              bytes.allSatisfy({ ($0 >= 48 && $0 <= 57) || ($0 >= 65 && $0 <= 90) || ($0 >= 97 && $0 <= 122) || $0 == 45 || $0 == 95 || $0 == 46 || $0 == 58 }) else {
            throw RelayGattProtocolError.invalidIdentifier
        }
    }
}

/// Creates the exact ordered write sequence expected by the Windows bridge:
/// START metadata, envelope CHUNKs, then a fragmented SHA-256 COMMIT digest.
public enum RelayGattUploadPlanner {
    public static func plan(
        encryptedEnvelope: Data,
        courierDeliveryId: String,
        carrierId: String,
        sessionId: Data
    ) throws -> [RelayGattFrame] {
        guard encryptedEnvelope.count <= RelayDeliveryLimits.maximumEnvelopeBytes,
              sessionId.count == RelayGattFrame.sessionIdBytes else {
            throw RelayGattProtocolError.invalidEnvelopeSize
        }
        let metadata = try RelayDeliveryStartMetadata(courierDeliveryId: courierDeliveryId, carrierId: carrierId)
        let digest = Data(SHA256.hash(data: encryptedEnvelope))
        return try RelayGattFrame.fragments(kind: .start, sessionId: sessionId, bytes: metadata.encoded())
            + RelayGattFrame.fragments(kind: .chunk, sessionId: sessionId, bytes: encryptedEnvelope)
            + RelayGattFrame.fragments(kind: .commit, sessionId: sessionId, bytes: digest)
    }
}

private extension Data {
    mutating func appendUInt16BE(_ value: UInt16) {
        append(UInt8(value >> 8))
        append(UInt8(value & 0xff))
    }
}
