# Signed offline distribution

`scripts/sign-artifacts.ps1 -ManifestOnly` creates integrity-only SHA-256 target
metadata without requiring private keys. Its output is explicitly not
authenticity-verified and must not be presented as a signed release. A release
build must additionally produce:

1. the artifact;
2. its `.bundle` and `.sig` from `cosign sign-blob --offline`;
3. SPDX SBOM;
4. TUF `targets.json` signed by the offline root key.

Verify both layers offline with `scripts/verify-distribution-signatures.ps1
-Offline -RequireBundles` and `scripts/verify-tuf-metadata.ps1
-TrustedRootPath <offline-trusted-root.json>`. The TUF verifier checks the
canonical signed payload, trusted key, expiry, target length, and SHA-256.
Never distribute a plaintext Gateway backup or an unsigned map pack.
