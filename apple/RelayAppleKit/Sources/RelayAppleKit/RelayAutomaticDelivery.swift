import Foundation

/// Thin adapter boundary for a CoreBluetooth central implementation.  The
/// adapter must serialize `.withResponse` writes and return bytes received
/// from the downlink indication.  Keeping it separate lets the automatic
/// delivery rules be tested without accepting an untrusted peripheral.
public protocol RelayTrustedGattLink: Sendable {
    func readIdentity() async throws -> Data
    func write(_ frame: RelayGattFrame) async throws
    func receiveResult(for sessionId: Data) async throws -> Data
}

/// A host-owned verifier for the signed Gateway receipt.  `true` means that
/// the receipt signature, envelope ID/version/hash and destination shelter all
/// match the parcel.  A bridge response alone is never sufficient.
public protocol RelayGatewayReceiptVerifier: Sendable {
    func verifies(receipt: Data, for parcel: RelayOpaqueCourierParcel) -> Bool
}

public enum RelayAutomaticDeliveryResult: Equatable, Sendable {
    case noPendingParcels
    case rejectedUntrustedShelter
    case receiptRejected
    case delivered(deliveryId: String)
}

/// Executes the user-no-action courier workflow for one already-connected
/// bridge.  It fails closed before any opaque envelope bytes leave the phone:
/// the bridge identity must resolve uniquely in the verified shelter directory
/// and the receipt must validate before persistent state changes.
public actor RelayAutomaticDeliveryCoordinator {
    private let store: RelayCourierStore
    private let directory: RelayVerifiedShelterDirectory
    private let receiptVerifier: RelayGatewayReceiptVerifier
    private let carrierId: String

    public init(
        store: RelayCourierStore,
        directory: RelayVerifiedShelterDirectory,
        receiptVerifier: RelayGatewayReceiptVerifier,
        carrierId: String
    ) throws {
        guard !carrierId.isEmpty else { throw RelayGattProtocolError.invalidIdentifier }
        self.store = store
        self.directory = directory
        self.receiptVerifier = receiptVerifier
        self.carrierId = carrierId
    }

    /// Called automatically by the CoreBluetooth adapter when a Relay service
    /// connection becomes usable.  At most one parcel is delivered per call so
    /// disconnect/retry behavior stays idempotent and bounded.
    public func deliverNext(using link: RelayTrustedGattLink, nowEpochMillis: Int64) async throws -> RelayAutomaticDeliveryResult {
        let identity = try RelayShelterBleIdentity(encoded: try await link.readIdentity())
        guard directory.resolves(identity) else { return .rejectedUntrustedShelter }
        guard let destinationShelterId = directory.destinationShelterId(for: identity) else {
            return .noPendingParcels
        }
        let pending = try await store.pending(nowEpochMillis: nowEpochMillis)
        guard let parcel = pending.first(where: { $0.destinationShelterId == destinationShelterId }) else {
            return .noPendingParcels
        }

        let sessionId = randomSessionId()
        let frames = try RelayGattUploadPlanner.plan(
            encryptedEnvelope: parcel.encryptedEnvelope,
            courierDeliveryId: parcel.deliveryId,
            carrierId: carrierId,
            sessionId: sessionId
        )
        for frame in frames { try await link.write(frame) }
        let receipt = try await link.receiveResult(for: sessionId)
        guard receiptVerifier.verifies(receipt: receipt, for: parcel) else { return .receiptRejected }
        try await store.markDelivered(deliveryId: parcel.deliveryId, verifiedReceipt: receipt)
        return .delivered(deliveryId: parcel.deliveryId)
    }

    private func randomSessionId() -> Data {
        Data((0..<RelayGattFrame.sessionIdBytes).map { _ in UInt8.random(in: .min ... .max) })
    }
}
