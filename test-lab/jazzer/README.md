# Jazzer lane

The parser fuzz target should call the same Relay packet/envelope decoder used
by production code and assert that malformed input is rejected without a
process crash. Until a dedicated Gradle fuzz target is declared, CI keeps the
The repository always runs the deterministic decoder regression corpus under
`test-lab/fuzz` and the BLE virtual transport corpus. When a JVM Jazzer
dependency/target is provisioned, `JAZZER_FUZZ=1` is the explicit opt-in for
that deeper lane; it must not be represented as coverage when unavailable.
