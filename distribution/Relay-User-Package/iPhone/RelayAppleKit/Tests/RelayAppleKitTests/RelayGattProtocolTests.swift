import CryptoKit
import XCTest
@testable import RelayAppleKit

final class RelayGattProtocolTests: XCTestCase {
    func testPlanUsesMtuSafeFramesAndCommitDigest() throws {
        let envelope = Data(repeating: 0x5a, count: 29)
        let session = Data([1, 2, 3, 4])
        let plan = try RelayGattUploadPlanner.plan(
            encryptedEnvelope: envelope,
            courierDeliveryId: "parcel-1",
            carrierId: "courier-1",
            sessionId: session
        )

        XCTAssertTrue(plan.allSatisfy { $0.encoded().count <= 20 })
        XCTAssertEqual(plan.first?.kind, .start)
        XCTAssertEqual(plan.filter { $0.kind == .chunk }.count, 3)
        let commit = plan.filter { $0.kind == .commit }.reduce(into: Data()) { $0.append($1.payload) }
        XCTAssertEqual(commit, Data(SHA256.hash(data: envelope)))
        XCTAssertEqual(try RelayGattFrame.decode(plan[1].encoded()), plan[1])
    }

    func testUnknownOrAmbiguousShelterIsRejected() throws {
        var bytes = Data([2])
        bytes.append(Data(repeating: 3, count: 9))
        let identity = try RelayShelterBleIdentity(encoded: bytes)
        let known = try RelayVerifiedShelter(signedManifestFingerprintPrefix: Data(repeating: 3, count: 9))
        XCTAssertTrue(RelayVerifiedShelterDirectory(verifiedShelters: [known]).resolves(identity))
        XCTAssertFalse(RelayVerifiedShelterDirectory(verifiedShelters: [known, known]).resolves(identity))
    }

    func testCourierStorePersistsOpaquePendingParcel() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let parcel = try RelayOpaqueCourierParcel(
            envelopeId: "envelope-1",
            requestVersion: 1,
            destinationShelterId: "shelter-1",
            expiresAtEpochMillis: 2_000,
            ciphertextSha256Hex: String(repeating: "a", count: 64),
            encryptedEnvelope: Data([1, 2, 3])
        )
        let store = try RelayCourierStore(directory: directory)
        try await store.upsert(parcel, nowEpochMillis: 1_000)
        let reloaded = try RelayCourierStore(directory: directory)
        let pending = try await reloaded.pending(nowEpochMillis: 1_000)
        XCTAssertEqual(pending, [parcel])
    }
}
