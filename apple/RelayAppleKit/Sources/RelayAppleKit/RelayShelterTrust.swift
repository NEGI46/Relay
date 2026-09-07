import Foundation

/// Legacy-advertisement-safe identity exposed by the Windows BLE bridge:
/// `version(2) | signed-manifest SHA-256 prefix(9)`.
public struct RelayShelterBleIdentity: Equatable, Sendable {
    public static let byteCount = 10
    public let signedManifestFingerprintPrefix: Data

    public init(encoded: Data) throws {
        guard encoded.count == Self.byteCount, encoded[0] == 2 else {
            throw RelayGattProtocolError.invalidFrame
        }
        signedManifestFingerprintPrefix = encoded.subdata(in: 1..<10)
    }
}

/// The app host must construct these only from a regional directory whose
/// detached signature has already been verified with the pinned regional root
/// key.  This package deliberately has no permissive fallback or "accept first
/// shelter" path.
public struct RelayVerifiedShelter: Equatable, Sendable {
    public let signedManifestFingerprintPrefix: Data
    public let shelterId: String?

    public init(signedManifestFingerprintPrefix: Data, shelterId: String? = nil) throws {
        guard signedManifestFingerprintPrefix.count == 9 else {
            throw RelayGattProtocolError.invalidFrame
        }
        self.signedManifestFingerprintPrefix = signedManifestFingerprintPrefix
        self.shelterId = shelterId
    }
}

public struct RelayVerifiedShelterDirectory: Sendable {
    private let shelters: [RelayVerifiedShelter]

    public init(verifiedShelters: [RelayVerifiedShelter]) { shelters = verifiedShelters }

    /// Rejects unknown and ambiguous advertised identities.  The caller must
    /// read the identity characteristic after connection and invoke this again
    /// before writing any encrypted parcel.
    public func resolves(_ advertised: RelayShelterBleIdentity) -> Bool {
        shelters.filter { shelter in
            shelter.signedManifestFingerprintPrefix == advertised.signedManifestFingerprintPrefix
        }.count == 1
    }

    /// Returns the configured shelter ID only when the fingerprint resolves uniquely. A host that
    /// has not yet loaded IDs must fail closed instead of sending a parcel to an arbitrary shelter.
    public func destinationShelterId(for advertised: RelayShelterBleIdentity) -> String? {
        let matches = shelters.filter { $0.signedManifestFingerprintPrefix == advertised.signedManifestFingerprintPrefix }
        guard matches.count == 1 else { return nil }
        return matches[0].shelterId
    }
}
