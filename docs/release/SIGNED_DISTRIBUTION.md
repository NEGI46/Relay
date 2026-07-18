# Signed offline distribution

`scripts/sign-artifacts.ps1 -ManifestOnly` creates SHA-256 target metadata
without requiring private keys. A release build must additionally produce:

1. the artifact;
2. its `.bundle` and `.sig` from `cosign sign-blob --offline`;
3. SPDX SBOM;
4. TUF `targets.json` signed by the offline root key.

Verify both layers with `scripts/verify-distribution-signatures.ps1` and
`scripts/verify-tuf-metadata.ps1`. Never distribute a plaintext Gateway
backup or an unsigned map pack.
