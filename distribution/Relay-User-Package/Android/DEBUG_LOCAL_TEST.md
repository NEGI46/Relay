# Android debug APK: PC Gateway local test

This APK is for a local end-to-end demonstration, not disaster deployment.

1. Start `../Windows/RelayPcGateway/bin/pc-gateway.bat` on the PC.
2. Connect the Android phone and PC to the same local Wi-Fi.
3. Install `Relay-debug.apk`. If a previous Relay installation exists, uninstall it or clear its app data.
4. Open Relay and wait about 10 seconds before creating a rescue request.

The debug APK listens for the PC Gateway's local UDP announcement and then obtains that test PC's public rescue manifest automatically. No rescue text or private key is sent during this enrollment.

If the request form still reports that shelter verification information is unavailable, confirm that the Gateway remains running, Windows Firewall permits Private-network inbound TCP 8080 and UDP 42888, and that the phone is not on a client-isolated guest Wi-Fi.

Release builds do not use this trust-on-first-use test path. They require a regionally signed shelter directory provisioned before deployment.
