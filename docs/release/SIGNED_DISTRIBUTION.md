# Signed offline distribution

`scripts/sign-artifacts.ps1 -ManifestOnly` creates integrity-only SHA-256 target
metadata without requiring private keys. Its output is explicitly not
authenticity-verified and must not be presented as a signed release. A release
build must additionally produce:

1. the artifact;
2. its `.bundle` and `.sig` from `cosign sign-blob --offline`;
3. SPDX SBOM;
4. TUF `root.json`, `targets.json`, `snapshot.json`, and `timestamp.json`,
   signed by the offline root key and linked by version/length/SHA-256 metadata.

Verify both layers offline with `scripts/verify-distribution-signatures.ps1
-Offline -RequireBundles`, `scripts/verify-tuf-metadata-chain.py
distribution/metadata/tuf`, and `scripts/verify-tuf-metadata.ps1
-TrustedRootPath <offline-trusted-root.json>`. The chain verifier checks role
structure, expiry, parent metadata hashes/lengths, version links, target path
confinement, and artifact length/SHA-256. It intentionally does not replace
cryptographic signature verification; production releases must use the
trusted-root PowerShell verifier and cosign bundles as well.

The `-ManifestOnly` mode remains integrity-only and must not be described as a
signed release. Its empty signature arrays are accepted only by the chain
integrity test path.
Never distribute a plaintext Gateway backup or an unsigned map pack.
