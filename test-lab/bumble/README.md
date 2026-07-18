# Bumble BLE lane

Bumble is the virtual BLE/HCI lane for deterministic GATT fault cases. It is
not a substitute for the Android/Windows hardware E2E. The minimum scenarios
are connect, characteristic write, disconnect during a frame, reconnect, and
receipt completion. Store any generated corpus outside the repository unless
it is a small, reviewed regression fixture.
