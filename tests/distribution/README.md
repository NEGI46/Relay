# Distribution verification fixtures

Run `powershell -NoProfile -ExecutionPolicy Bypass -File tests/distribution/verify-distribution-fixtures.tests.ps1`.

The test generates an ephemeral RSA key under the OS temporary directory. No
private key or production signature is stored in the repository. It covers the
`-ManifestOnly` integrity-only round trip, expiry, length/hash mismatches,
trusted TUF signature verification, and placeholder-signature rejection.

`-ManifestOnly` is deliberately not authenticity verification. Production
verification requires a trusted-root file and a real TUF signature. Cosign
verification is separately performed offline with both `.sig` and `.bundle` by
`scripts/verify-distribution-signatures.ps1`.
