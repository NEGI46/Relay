# RelayAppleKit

`RelayAppleKit` is the source-level Apple client foundation for Relay's
automatic courier path.  It is not an IPA and it does not claim that a generic
iPhone can act as an always-on relay in every iOS background condition.

It provides these production-facing boundaries:

- the byte-exact BLE GATT framing and START metadata contract used by
  `pc-ble-bridge` (20-byte ATT-safe values);
- a file-backed queue for **opaque encrypted envelopes only**; no rescue text,
  people count, injury, location, or decryption key is modeled by the courier;
- fail-closed advertised-shelter matching.  A host app must inject records
  obtained from a signed regional directory verified with its pinned root key;
- a delivery plan containing START, CHUNK and SHA-256 COMMIT frames.  The
  host marks an item delivered only after it verifies the Gateway receipt.
- an automatic-delivery coordinator which sends one parcel after a trusted
  bridge connection and preserves it for retry after any disconnect.

## What remains in the signed iOS app host

The host owns the small CoreBluetooth adapter that implements
`RelayTrustedGattLink`, permissions, background execution, encrypted-at-rest
protection, signed-directory parsing, receipt verification, and UI.  It must
serialize `CBPeripheral.writeValue(..., type: .withResponse)`, read the bridge
identity characteristic after connection, and re-check
`RelayVerifiedShelterDirectory` immediately before writing a parcel.  It must
never use a scanned name, RSSI, or an unverified JSON file as a shelter trust
decision.

To build on macOS:

```bash
cd apple/RelayAppleKit
swift test
```

Then add this directory as a local Swift package to the Xcode app target.  The
target needs `NSBluetoothAlwaysUsageDescription`; deployment also needs the
operator's regional-root key, signed shelter directory, and Apple signing.

## Deliberate limits

This repository is currently verified on Windows only for Android/JVM code.
`swift test`, real CoreBluetooth sessions, iPhone background behavior and IPA
signing require macOS/Xcode and physical Apple hardware.  Do not distribute a
debug APK or this source package as a disaster-ready Apple release.
