# iOS Codemagic Setup Guide

Step-by-step instructions for configuring Codemagic CI/CD for the Relay iOS app.

> **Security Warning**: Never commit API keys, certificates, or provisioning profiles to the repository. All sensitive values are managed through Codemagic's encrypted environment variables.

---

## Prerequisites

Before starting, ensure you have:

- A Codemagic account (free tier supports 500 build minutes/month for iOS)
- An Apple Developer Program membership ($99/year) — required only for signed builds
- Access to the NEGI46/Relay GitHub repository

---

## Step 1: Connect Repository to Codemagic

1. Log in to [codemagic.io](https://codemagic.io)
2. Click **"Add application"**
3. Select **GitHub** as the repository provider
4. Authorize Codemagic to access your GitHub account (if not already done)
5. Select the repository: `NEGI46/Relay`
6. Choose **"codemagic.yaml"** as the project type (not Flutter or native iOS)
7. Click **"Finish: Add application"**

---

## Step 2: Configure Branch Detection

1. In the application settings, go to **"Build triggers"**
2. Ensure the branch pattern includes: `quest/ios-codemagic-foundation`
3. For production, add patterns for `main`, `agent/zero-operation-relay`
4. **Manual trigger only** is configured in the YAML — no automatic webhooks fire

---

## Step 3: Verify Simulator Build (No Signing Required)

1. Go to the app's **"Start new build"** button
2. Select branch: `quest/ios-codemagic-foundation`
3. Select workflow: **"iOS Simulator Smoke Build"**
4. Click **"Start new build"**
5. Wait for build completion (typically 10-20 minutes for first build with cache miss)
6. Download artifacts from the build page (`.app` bundle for Simulator)

> This workflow requires NO Apple Developer credentials. It produces an unsigned `.app` that runs only in the iOS Simulator.

---

## Step 4: Apple Developer Portal Setup (Signed Builds Only)

Skip this section if you only need simulator builds.

### 4a: Create App Store Connect API Key

1. Go to [App Store Connect → Users and Access → Integrations → App Store Connect API](https://appstoreconnect.apple.com/access/integrations/api)
2. Click the **"+"** button to create a new key
3. Name: `<REPLACE_ME>` (e.g., "Codemagic CI")
4. Access: **App Manager** (minimum required for signing)
5. Click **"Generate"**
6. **Download the `.p8` file immediately** — it can only be downloaded once
7. Note the **Key ID** and **Issuer ID** shown on the page

### 4b: Register Bundle ID

1. Go to [Apple Developer → Certificates, IDs & Profiles → Identifiers](https://developer.apple.com/account/resources/identifiers/list)
2. Click **"+"** → select **"App IDs"** → **"App"**
3. Description: `<REPLACE_ME>` (e.g., "Relay iOS")
4. Bundle ID (Explicit): `<REPLACE_ME>` (default: `com.example.relay.ios`, change to your real bundle ID)
5. Enable capabilities as needed (none required for initial build)
6. Click **"Register"**

### 4c: Create Distribution Certificate (if none exists)

Codemagic can auto-create certificates when given the API key. No manual action needed unless you prefer manual management.

---

## Step 5: Configure Codemagic Environment Variables

1. In Codemagic app settings, go to **"Environment variables"**
2. Add the following variables in a group named `ios_signing`:

| Variable | Value | Secure |
|----------|-------|--------|
| `APP_STORE_CONNECT_KEY_IDENTIFIER` | `<REPLACE_ME>` (Key ID from step 4a) | Yes |
| `APP_STORE_CONNECT_ISSUER_ID` | `<REPLACE_ME>` (Issuer ID from step 4a) | Yes |
| `APP_STORE_CONNECT_PRIVATE_KEY` | Contents of the `.p8` file | Yes |

3. Mark ALL variables as **"Secure"** (encrypted, not shown in logs)

> **Warning**: The `.p8` private key content should be pasted directly (including `-----BEGIN PRIVATE KEY-----` and `-----END PRIVATE KEY-----` lines). Never commit this file to the repository.

---

## Step 6: Update Bundle ID (If Changed)

If you changed the bundle ID from `com.example.relay.ios`:

1. Update `codemagic.yaml`:
   - `environment.ios_signing.bundle_identifier`
   - `environment.vars.BUNDLE_ID`
2. Update `composeApp/iosApp/project.yml`:
   - `settings.base.PRODUCT_BUNDLE_IDENTIFIER`
3. Run validation: `scripts/ios/validate-codemagic-config.sh`

---

## Step 7: Run Signed Archive Build

1. Click **"Start new build"**
2. Select branch: `quest/ios-codemagic-foundation`
3. Select workflow: **"iOS Signed Archive (Manual)"**
4. Click **"Start new build"**
5. Wait for build completion
6. Download the `.ipa` artifact from the build page

> The signed `.ipa` is NOT automatically uploaded to App Store Connect. Manual upload via Transporter app or `altool` is required for TestFlight distribution.

---

## Step 8: (Optional) Configure Build Notifications

1. In Codemagic app settings, go to **"Notifications"**
2. Add email addresses or Slack webhook for build status notifications
3. Configure which events trigger notifications (success, failure, or both)

---

## Troubleshooting

### Build fails at "Install XcodeGen"

XcodeGen is installed via Homebrew. If Homebrew is not available on the build machine, the build will fail. Codemagic's macOS images include Homebrew by default.

### Build fails at "Build unsigned iOS Simulator app"

Common causes:
- Gradle wrapper permissions not set (should be handled by the `chmod` step)
- Kotlin/Native compilation timeout — increase `max_build_duration` in `codemagic.yaml`
- Missing framework — ensure `embedAndSignAppleFrameworkForXcode` runs before compile

### Signing errors in archive workflow

- Verify the API key has not expired
- Ensure the bundle ID in Codemagic matches the one registered in Apple Developer Portal
- Check that the provisioning profile was successfully created (look for `xcode-project use-profiles` output)

### Cache issues

If builds are failing after toolchain changes, clear the cache:
1. Go to app settings → **"Caching"**
2. Click **"Clear cache"**
3. Re-run the build

---

## File Reference

| File | Purpose |
|------|---------|
| `codemagic.yaml` | CI/CD workflow definitions |
| `composeApp/iosApp/project.yml` | XcodeGen project specification |
| `composeApp/iosApp/RelayIOS/` | Swift source files for iOS host |
| `scripts/ios/install-xcodegen.sh` | XcodeGen installation script |
| `scripts/ios/generate-xcode-project.sh` | Xcode project generation script |
| `scripts/ios/validate-codemagic-config.sh` | Configuration validation script |
| `docs/IOS_BUILD_STATUS.md` | Current build and test status |

---

## Security Checklist

Before merging to production:

- [ ] No `.p8`, `.p12`, or `.mobileprovision` files in the repository
- [ ] No hardcoded Development Team ID in `project.yml`
- [ ] `CODE_SIGN_IDENTITY` is empty string (unsigned) in simulator workflow
- [ ] All secrets stored in Codemagic environment variables (marked Secure)
- [ ] No `NSAllowsArbitraryLoads = true` in Info.plist
- [ ] `ITSAppUsesNonExemptEncryption` reviewed before App Store submission
