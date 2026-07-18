import Foundation

/// Parsed identity value advertised and exposed by the Windows BLE bridge:
/// `version(1) | shelter-id SHA-256 prefix(8) | signed-manifest SHA-256
/// prefix(16) | session hint(8)`.  It cannot reveal rescue content.
public struct RelayShelterBleIdentity: Equatable, Sendable {
    public static let byteCount = 33
    public let shelterIdHashPrefix: Data
    public let signedManifestFingerprintPrefix: Data
    public let sessionHint: Data

    public init(encoded: Data) throws {
        guard encoded.count == Self.byteCount, encoded[0] == 1 else {
            throw RelayGattProtocolError.invalidFrame
        }
        shelterIdHashPrefix = encoded.subdata(in: 1..<9)
        signedManifestFingerprintPrefix = encoded.subdata(in: 9..<25)
        sessionHint = encoded.subdata(in: 25..<33)
    }
}

/// The app host must construct these only from a regional directory whose
/// detached signature has already been verified with the pinned regional root
/// key.  This package deliberately has no permissive fallback or "accept first
/// shelter" path.
public struct RelayVerifiedShelter: Equatable, Sendable {
    public let shelterIdHashPrefix: Data
    public let signedManifestFingerprintPrefix: Data

    public init(shelterIdHashPrefix: Data, signedManifestFingerprintPrefix: Data) throws {
        guard shelterIdHashPrefix.count == 8, signedManifestFingerprintPrefix.count == 16 else {
            throw RelayGattProtocolError.invalidFrame
        }
        self.shelterIdHashPrefix = shelterIdHashPrefix
        self.signedManifestFingerprintPrefix = signedManifestFingerprintPrefix
    }
}

public struct RelayVerifiedShelterDirectory: Sendable {
    private let shelters: [RelayVerifiedShelter]

    public init(verifiedShelters: [RelayVerifiedShelter]) { shelters = verifiedShelters }

    /// Rejects unknown and ambiguous advertised identities.  The caller must
    /// read the identity characteristic after connection and invoke this again
    /// before writing any encrypted parcel.
    public func resolves(_ advertised: RelayShelterBleIdentity) -> Bool {
        shelters.filter {
            $0.shelterIdHashPrefix == advertised.shelterIdHashPrefix &&
            $0.signedManifestFingerprintPrefix == advertised.signedManifestFingerprintPrefix
        }.count == 1
    }
}
