using System.Security.Cryptography;
using System.Text;

namespace Relay.PcBleBridge.Protocol;

/// <summary>
/// The only payload permitted in the BLE advertisement.  All values are
/// delivery metadata; no rescue request data belongs here.
/// </summary>
public sealed record BleIdentityAdvertisement(
    byte ProtocolVersion,
    byte[] ShelterIdHash,
    byte[] SignedManifestFingerprint,
    byte[] SessionHint)
{
    public const int ShelterIdHashBytes = 8;
    public const int ManifestFingerprintBytes = 16;
    public const int SessionHintBytes = 8;

    public static BleIdentityAdvertisement Create(
        string shelterId,
        ReadOnlySpan<byte> signedManifestFingerprint,
        ReadOnlySpan<byte> sessionHint)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(shelterId);
        if (signedManifestFingerprint.Length < ManifestFingerprintBytes)
            throw new ArgumentException("Manifest fingerprint is too short.", nameof(signedManifestFingerprint));
        if (sessionHint.Length != SessionHintBytes)
            throw new ArgumentException("Session hint must be eight bytes.", nameof(sessionHint));

        var shelterHash = SHA256.HashData(Encoding.UTF8.GetBytes(shelterId));
        return new BleIdentityAdvertisement(
            ProtocolVersion: 1,
            ShelterIdHash: shelterHash[..ShelterIdHashBytes],
            SignedManifestFingerprint: signedManifestFingerprint[..ManifestFingerprintBytes].ToArray(),
            SessionHint: sessionHint.ToArray());
    }

    public byte[] Encode()
    {
        if (ShelterIdHash.Length != ShelterIdHashBytes ||
            SignedManifestFingerprint.Length != ManifestFingerprintBytes ||
            SessionHint.Length != SessionHintBytes)
            throw new InvalidOperationException("Invalid advertisement identity lengths.");

        return [ProtocolVersion, .. ShelterIdHash, .. SignedManifestFingerprint, .. SessionHint];
    }
}
