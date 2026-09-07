import Foundation

/// An opaque parcel is the only rescue data an automatic courier is allowed to
/// retain.  This type intentionally has no field for injury, location, people,
/// note, or decrypted rescue payload.
public struct RelayOpaqueCourierParcel: Codable, Equatable, Sendable {
    public enum Status: String, Codable, Sendable { case pending, delivered }

    public let envelopeId: String
    public let requestVersion: Int
    public let destinationShelterId: String
    public let expiresAtEpochMillis: Int64
    public let ciphertextSha256Hex: String
    public let encryptedEnvelope: Data
    public var status: Status
    public var receipt: Data?

    public init(
        envelopeId: String,
        requestVersion: Int,
        destinationShelterId: String,
        expiresAtEpochMillis: Int64,
        ciphertextSha256Hex: String,
        encryptedEnvelope: Data,
        status: Status = .pending,
        receipt: Data? = nil
    ) throws {
        guard Self.isIdentifier(envelopeId), requestVersion >= 1,
              Self.isIdentifier(destinationShelterId), ciphertextSha256Hex.count == 64,
              encryptedEnvelope.count > 0, encryptedEnvelope.count <= RelayDeliveryLimits.maximumEnvelopeBytes else {
            throw RelayGattProtocolError.invalidEnvelopeSize
        }
        self.envelopeId = envelopeId
        self.requestVersion = requestVersion
        self.destinationShelterId = destinationShelterId
        self.expiresAtEpochMillis = expiresAtEpochMillis
        self.ciphertextSha256Hex = ciphertextSha256Hex.lowercased()
        self.encryptedEnvelope = encryptedEnvelope
        self.status = status
        self.receipt = receipt
    }

    public var deliveryId: String { "\(envelopeId):\(requestVersion)" }

    private static func isIdentifier(_ value: String) -> Bool {
        let bytes = Array(value.utf8)
        return !bytes.isEmpty && bytes.count <= 128 && bytes.allSatisfy { byte in
            (byte >= 48 && byte <= 57) || (byte >= 65 && byte <= 90) || (byte >= 97 && byte <= 122) || byte == 45 || byte == 95 || byte == 46 || byte == 58
        }
    }
}

/// Crash-safe, file-backed courier queue.  A successful signed receipt is
/// persisted before an item becomes delivered, so reconnecting can safely
/// retry a pending parcel.  The receipt signature itself is verified by the
/// app host against the trusted shelter directory before calling
/// `markDelivered`.
public actor RelayCourierStore {
    private let fileURL: URL
    private var parcels: [String: RelayOpaqueCourierParcel]

    public init(directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("relay-courier-parcels.json")
        if let data = try? Data(contentsOf: fileURL) {
            parcels = try JSONDecoder().decode([String: RelayOpaqueCourierParcel].self, from: data)
        } else {
            parcels = [:]
        }
    }

    public func upsert(_ parcel: RelayOpaqueCourierParcel, nowEpochMillis: Int64) throws {
        guard parcel.expiresAtEpochMillis > nowEpochMillis else { return }
        let key = parcel.deliveryId
        if let existing = parcels[key] {
            guard existing.ciphertextSha256Hex == parcel.ciphertextSha256Hex,
                  existing.encryptedEnvelope == parcel.encryptedEnvelope,
                  existing.destinationShelterId == parcel.destinationShelterId else { return }
            // A retransmitted pending copy must never roll a verified delivery back to pending
            // or discard the receipt that makes the delivery durable.
            guard existing.status != .delivered else { return }
            parcels[key] = try RelayOpaqueCourierParcel(
                envelopeId: existing.envelopeId,
                requestVersion: existing.requestVersion,
                destinationShelterId: existing.destinationShelterId,
                expiresAtEpochMillis: max(existing.expiresAtEpochMillis, parcel.expiresAtEpochMillis),
                ciphertextSha256Hex: existing.ciphertextSha256Hex,
                encryptedEnvelope: existing.encryptedEnvelope,
                status: existing.status,
                receipt: existing.receipt
            )
        } else {
            parcels[key] = parcel
        }
        try persist()
    }

    public func pending(nowEpochMillis: Int64) throws -> [RelayOpaqueCourierParcel] {
        let expired = parcels.filter { $0.value.expiresAtEpochMillis <= nowEpochMillis }.map(\.key)
        expired.forEach { parcels.removeValue(forKey: $0) }
        if !expired.isEmpty { try persist() }
        return parcels.values.filter { $0.status == .pending }.sorted { $0.expiresAtEpochMillis < $1.expiresAtEpochMillis }
    }

    public func markDelivered(deliveryId: String, verifiedReceipt: Data) throws {
        guard !verifiedReceipt.isEmpty, var parcel = parcels[deliveryId] else { return }
        parcel.status = .delivered
        parcel.receipt = verifiedReceipt
        parcels[deliveryId] = parcel
        try persist()
    }

    private func persist() throws {
        let data = try JSONEncoder().encode(parcels)
        let temporary = fileURL.deletingLastPathComponent().appendingPathComponent(".relay-courier-parcels-\(UUID().uuidString).tmp")
        try data.write(to: temporary, options: [.atomic])
        if FileManager.default.fileExists(atPath: fileURL.path) {
            _ = try FileManager.default.replaceItemAt(fileURL, withItemAt: temporary, backupItemName: nil, options: [])
        } else {
            try FileManager.default.moveItem(at: temporary, to: fileURL)
        }
    }
}
