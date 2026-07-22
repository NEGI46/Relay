# Verify a Relay formal release

Use this procedure only with a release that passed the formal GitHub workflow. A locally built, debug, unsigned, self-signed, or incomplete artifact is not a formal release.

## 1. Establish the source commit

Read `build-source.json`, `android-build-info.json`, and `windows-build-info.json`. All `sourceCommit` values must be the same full 40-character Git commit SHA. Compare it with the tag target in GitHub and the reviewed source revision before installing anything.

## 2. Check file hashes

From the downloaded release directory, verify the included hashes:

```bash
sha256sum --check SHA256SUMS
```

On Windows, calculate the hash independently with `Get-FileHash -Algorithm SHA256` and compare it to `SHA256SUMS`. A mismatch is a stop condition.

## 3. Verify platform signing

For Android, use a trusted Android SDK build-tools installation:

```bash
apksigner verify --verbose --print-certs Relay-Android-release.apk
```

Compare the certificate identity/fingerprint against the organization-approved Android signing certificate. The included `android-signature-verification.txt` is evidence, not a substitute for an independent verification.

For Windows, on a representative Windows host:

```powershell
signtool verify /pa /all /v Relay-PC-Gateway-setup.exe
```

Compare the publisher and timestamp against the organization-approved Authenticode policy. Do not accept a self-signed certificate as production signing.

## 4. Verify artifact and TUF signatures

Obtain the cosign public key and TUF trusted root through the organization’s separate trust channel, never from an unverified release asset alone. Extract the included metadata first, then verify the APK and installer signatures/bundles and their TUF target entries with the repository verification tools:

```powershell
tar -xzf <release-directory>/Relay-TUF-metadata.tar.gz -C <release-directory>
./scripts/verify-distribution-signatures.ps1 -PackageRoot <release-directory> -PublicKey <approved-cosign-public-key> -Offline -RequireBundles -RequireVerification
./scripts/verify-tuf-metadata.ps1 -MetadataPath <release-directory>/tuf/targets.json -ArtifactPath <release-directory>/Relay-Android-release.apk -TargetName Relay-Android-release.apk -TrustedRootPath <approved-tuf-root.json>
./scripts/verify-tuf-metadata.ps1 -MetadataPath <release-directory>/tuf/targets.json -ArtifactPath <release-directory>/Relay-PC-Gateway-setup.exe -TargetName Relay-PC-Gateway-setup.exe -TrustedRootPath <approved-tuf-root.json>
```

`signature-verification.json`, the SBOM files, and the vulnerability evidence must all be present and report `PASS`. A missing tool, root, bundle, SBOM, or report is `BLOCKED`, not a pass.

## 5. Preserve evidence

Record the tag, source commit, verified certificate fingerprints, checksum result, verification date, verifier, and any `BLOCKED`/failure result. Do not record private keys, passwords, raw Broker credentials, rescue content, or GPS.
